package org.mediasoup.droid.lib.socket;

import android.os.Handler;
import android.os.HandlerThread;

import org.mediasoup.droid.lib.lv.TLSSocketFactory;

import org.json.JSONObject;
import org.mediasoup.droid.Logger;
import org.protoojs.droid.Message;
import org.protoojs.droid.transports.AbsWebSocketTransport;

import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketTransport extends AbsWebSocketTransport {

    // Log tag.
    private static final String TAG = "WebSocketTransport";
    // Closed flag.
    private boolean mClosed;
    // Connected flag.
    private boolean mConnected;
    // OKHttpClient.
    private final OkHttpClient mOkHttpClient;
    // Handler associate to current thread.
    private final Handler mHandler;
    // Retry operation.
    private final RetryStrategy mRetryStrategy;
    // WebSocket instance.
    private WebSocket mWebSocket;
    // Listener.
    private Listener mListener;

    private static class RetryStrategy {

        private final int retries;
        private final int factor;
        private final int minTimeout;
        private final int maxTimeout;

        private int retryCount = 1;

        RetryStrategy(int retries, int factor, int minTimeout, int maxTimeout) {
            this.retries = retries;
            this.factor = factor;
            this.minTimeout = minTimeout;
            this.maxTimeout = maxTimeout;
        }

        void retried() {
            retryCount++;
        }

        int getReconnectInterval() {
            if (retryCount > retries) {
                return -1;
            }
            int reconnectInterval = (int) (minTimeout * Math.pow(factor, retryCount));
            reconnectInterval = Math.min(reconnectInterval, maxTimeout);
            return reconnectInterval;
        }

        void reset() {
            if (retryCount != 0) {
                retryCount = 0;
            }
        }
    }

    public WebSocketTransport(String url) {
        super(url);
        mOkHttpClient = getUnsafeOkHttpClient();
        HandlerThread handlerThread = new HandlerThread("socket");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mRetryStrategy = new RetryStrategy(10, 2, 1000, 8 * 1000);
    }

    @Override
    public void connect(Listener listener) {
        Logger.d(TAG, "connect()");
        mListener = listener;
        mHandler.post(this::newWebSocket);
    }

    private void newWebSocket() {
        mWebSocket = null;
        mOkHttpClient.newWebSocket(new Request.Builder().url(mUrl).addHeader("Sec-WebSocket-Protocol", "protoo").build(), new ProtooWebSocketListener());
    }

    private boolean scheduleReconnect() {
        int reconnectInterval = mRetryStrategy.getReconnectInterval();
        if (reconnectInterval == -1) {
            return false;
        }
        Logger.d(TAG, "scheduleReconnect() ");
        mHandler.postDelayed(() -> {
            if (mClosed) {
                return;
            }
            Logger.w(TAG, "doing reconnect job, retryCount: " + mRetryStrategy.retryCount);
            mOkHttpClient.dispatcher().cancelAll();
            newWebSocket();
            mRetryStrategy.retried();
        }, reconnectInterval);
        return true;
    }

    @Override
    public String sendMessage(JSONObject message) {
        if (mClosed) {
            throw new IllegalStateException("transport closed");
        }
        String payload = message.toString();
        mHandler.post(() -> {
            if (mClosed) {
                return;
            }
            if (mWebSocket != null) {
                mWebSocket.send(payload);
            }
        });
        return payload;
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        Logger.d(TAG, "close()");
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        mHandler.post(() -> {
            if (mWebSocket != null) {
                mWebSocket.close(1000, "bye");
                mWebSocket = null;
            }
            countDownLatch.countDown();
        });
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isClosed() {
        return mClosed;
    }

    private class ProtooWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (mClosed) {
                return;
            }
            Logger.d(TAG, "onOpen() ");
            mWebSocket = webSocket;
            mConnected = true;
            if (mListener != null) {
                mListener.onOpen();
            }
            mRetryStrategy.reset();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Logger.w(TAG, "onClosed()");
            if (mClosed) {
                return;
            }
            mClosed = true;
            mConnected = false;
            mRetryStrategy.reset();
            if (mListener != null) {
                mListener.onClose();
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Logger.w(TAG, "onClosing()");
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Logger.w(TAG, "onFailure()" + t.toString());

            // onFailure()javax.net.ssl.SSLHandshakeException: javax.net.ssl.SSLProtocolException: SSL handshake aborted: ssl=0x616d4370: Failure in SSL library, usually a protocol error
            // error:1407742E:SSL routines:SSL23_GET_SERVER_HELLO:tlsv1 alert protocol version (external/openssl/ssl/s23_clnt.c:744 0x5c233cfc:0x00000000)

            if (mClosed) {
                return;
            }

            if (scheduleReconnect()) {
                if (mListener != null) {
                    if (mConnected) {
                        mListener.onFail();
                    } else {
                        mListener.onDisconnected();
                    }
                }
            } else {
                Logger.e(TAG, "give up reconnect. notify closed");
                mClosed = true;
                if (mListener != null) {
                    mListener.onClose();
                }
                mRetryStrategy.reset();
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Logger.d(TAG, "onMessage()");
            if (mClosed) {
                return;
            }
            Message message = Message.parse(text);
            if (message == null) {
                return;
            }
            if (mListener != null) {
                mListener.onMessage(message);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            Logger.d(TAG, "onMessage()");
        }
    }

    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                // Called reflectively by X509TrustManagerExtensions.
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType, String host) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }};

            // ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS).supportsTlsExtensions(true).tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0).cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256, CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA, CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA, CipherSuite.TLS_ECDHE_ECDSA_WITH_RC4_128_SHA, CipherSuite.TLS_ECDHE_RSA_WITH_RC4_128_SHA, CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA, CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA, CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA).build();

//            final SSLContext sslContext = SSLContext.getInstance(SSL);
//            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
//
//            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            final SSLSocketFactory sslSocketFactory = new TLSSocketFactory();

            // HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor(s -> Logger.d(TAG, s));
            // httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

            // .addInterceptor(httpLoggingInterceptor)
            final OkHttpClient.Builder builder = new OkHttpClient.Builder().retryOnConnectionFailure(true).sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]).hostnameVerifier((hostname, session) -> true);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
