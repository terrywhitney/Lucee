/**
 *
 * Copyright (c) 2014, the Railo Company Ltd. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 *
 **/
package lucee.commons.net.http.httpclient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpMessage;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultClientConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import lucee.commons.io.IOUtil;
import lucee.commons.io.TemporaryStream;
import lucee.commons.io.log.Log;
import lucee.commons.io.log.LogUtil;
import lucee.commons.io.res.Resource;
import lucee.commons.lang.ExceptionUtil;
import lucee.commons.lang.StringUtil;
import lucee.commons.net.http.Entity;
import lucee.commons.net.http.HTTPEngine;
import lucee.commons.net.http.HTTPResponse;
import lucee.commons.net.http.httpclient.entity.ByteArrayHttpEntity;
import lucee.commons.net.http.httpclient.entity.EmptyHttpEntity;
import lucee.commons.net.http.httpclient.entity.ResourceHttpEntity;
import lucee.commons.net.http.httpclient.entity.TemporaryStreamHttpEntity;
import lucee.runtime.PageContextImpl;
import lucee.runtime.engine.ThreadLocalPageContext;
import lucee.runtime.exp.PageException;
import lucee.runtime.net.http.ReqRspUtil;
import lucee.runtime.net.http.sni.DefaultHostnameVerifierImpl;
import lucee.runtime.net.http.sni.DefaultHttpClientConnectionOperatorImpl;
import lucee.runtime.net.http.sni.SSLConnectionSocketFactoryImpl;
import lucee.runtime.net.proxy.ProxyData;
import lucee.runtime.net.proxy.ProxyDataImpl;
import lucee.runtime.op.Caster;
import lucee.runtime.op.Decision;
import lucee.runtime.tag.Http;
import lucee.runtime.type.dt.TimeSpan;
import lucee.runtime.type.dt.TimeSpanImpl;
import lucee.runtime.type.util.CollectionUtil;

public class HTTPEngine4Impl {

	private static Field isShutDownField;
	private static Map<String, PoolingHttpClientConnectionManager> connectionManagers = new ConcurrentHashMap<>();
	private static boolean cannotAccess = false;

	public static final int POOL_MAX_CONN = 500;
	public static final int POOL_MAX_CONN_PER_ROUTE = 50;
	public static final int POOL_CONN_TTL_MS = 15000;
	// public static final int POOL_CONN_INACTIVITY_DURATION = 300;
	private static final long SHUTDOWN_CHECK_MAX_AGE = 10000;

	/**
	 * does a http get request
	 * 
	 * @param url
	 * @param username
	 * @param password
	 * @param timeout
	 * @param charset
	 * @param useragent
	 * @param proxyserver
	 * @param proxyport
	 * @param proxyuser
	 * @param proxypassword
	 * @param headers
	 * @return
	 * @throws IOException
	 * @throws GeneralSecurityException
	 */
	public static HTTPResponse get(URL url, String username, String password, long timeout, boolean redirect, String charset, String useragent, ProxyData proxy,
			lucee.commons.net.http.Header[] headers) throws IOException, GeneralSecurityException {
		HttpGet get = new HttpGet(url.toExternalForm());
		return invoke(url, get, username, password, timeout, redirect, charset, useragent, proxy, headers, null, false);
	}

	/**
	 * does a http post request
	 * 
	 * @param url
	 * @param username
	 * @param password
	 * @param timeout
	 * @param charset
	 * @param useragent
	 * @param proxyserver
	 * @param proxyport
	 * @param proxyuser
	 * @param proxypassword
	 * @param headers
	 * @return
	 * @throws IOException
	 * @throws GeneralSecurityException
	 */
	public static HTTPResponse post(URL url, String username, String password, long timeout, boolean redirect, String charset, String useragent, ProxyData proxy,
			lucee.commons.net.http.Header[] headers) throws IOException, GeneralSecurityException {
		HttpPost post = new HttpPost(url.toExternalForm());
		return invoke(url, post, username, password, timeout, redirect, charset, useragent, proxy, headers, null, false);
	}

	public static HTTPResponse post(URL url, String username, String password, long timeout, boolean redirect, String charset, String useragent, ProxyData proxy,
			lucee.commons.net.http.Header[] headers, Map<String, String> formfields) throws IOException, GeneralSecurityException {
		HttpPost post = new HttpPost(url.toExternalForm());

		return invoke(url, post, username, password, timeout, redirect, charset, useragent, proxy, headers, formfields, false);
	}

	/**
	 * does a http put request
	 * 
	 * @param url
	 * @param username
	 * @param password
	 * @param timeout
	 * @param charset
	 * @param useragent
	 * @param proxyserver
	 * @param proxyport
	 * @param proxyuser
	 * @param proxypassword
	 * @param headers
	 * @param body
	 * @return
	 * @throws IOException
	 * @throws GeneralSecurityException
	 * @throws PageException
	 */
	public static HTTPResponse put(URL url, String username, String password, long timeout, boolean redirect, String mimetype, String charset, String useragent, ProxyData proxy,
			lucee.commons.net.http.Header[] headers, Object body) throws IOException, GeneralSecurityException {
		HttpPut put = new HttpPut(url.toExternalForm());
		setBody(put, body, mimetype, charset);
		return invoke(url, put, username, password, timeout, redirect, charset, useragent, proxy, headers, null, false);

	}

	/**
	 * does a http delete request
	 * 
	 * @param url
	 * @param username
	 * @param password
	 * @param timeout
	 * @param charset
	 * @param useragent
	 * @param proxyserver
	 * @param proxyport
	 * @param proxyuser
	 * @param proxypassword
	 * @param headers
	 * @return
	 * @throws IOException
	 * @throws GeneralSecurityException
	 */
	public static HTTPResponse delete(URL url, String username, String password, long timeout, boolean redirect, String charset, String useragent, ProxyData proxy,
			lucee.commons.net.http.Header[] headers) throws IOException, GeneralSecurityException {
		HttpDelete delete = new HttpDelete(url.toExternalForm());
		return invoke(url, delete, username, password, timeout, redirect, charset, useragent, proxy, headers, null, false);
	}

	/**
	 * does a http head request
	 * 
	 * @param url
	 * @param username
	 * @param password
	 * @param timeout
	 * @param charset
	 * @param useragent
	 * @param proxyserver
	 * @param proxyport
	 * @param proxyuser
	 * @param proxypassword
	 * @param headers
	 * @return
	 * @throws IOException
	 * @throws GeneralSecurityException
	 */
	public static HTTPResponse head(URL url, String username, String password, long timeout, boolean redirect, String charset, String useragent, ProxyData proxy,
			lucee.commons.net.http.Header[] headers) throws IOException, GeneralSecurityException {
		HttpHead head = new HttpHead(url.toExternalForm());
		return invoke(url, head, username, password, timeout, redirect, charset, useragent, proxy, headers, null, false);
	}

	public static lucee.commons.net.http.Header header(String name, String value) {
		return new HeaderImpl(name, value);
	}

	private static Header toHeader(lucee.commons.net.http.Header header) {
		if (header instanceof Header) return (Header) header;
		if (header instanceof HeaderWrap) return ((HeaderWrap) header).header;
		return new HeaderImpl(header.getName(), header.getValue());
	}

	public static HttpClientBuilder getHttpClientBuilder(boolean pooling, String clientCert, String clientCertPassword) throws GeneralSecurityException, IOException {
		String key = clientCert + ":" + clientCertPassword;
		Registry<ConnectionSocketFactory> reg = StringUtil.isEmpty(clientCert, true) ? createRegistry() : createRegistry(clientCert, clientCertPassword);

		if (!pooling) {
			HttpClientBuilder builder = HttpClients.custom();
			HttpClientConnectionManager cm = new BasicHttpClientConnectionManager(new DefaultHttpClientConnectionOperatorImpl(reg), null);
			builder.setConnectionManager(cm).setConnectionManagerShared(false);
			return builder;
		}

		PoolingHttpClientConnectionManager cm = connectionManagers.get(key);
		if (cm == null || isShutDown(cm, true)) {

			// if (connMan == null || isShutDown(true)) {
			cm = new PoolingHttpClientConnectionManager(new DefaultHttpClientConnectionOperatorImpl(reg), null, POOL_CONN_TTL_MS, TimeUnit.MILLISECONDS);
			cm.setDefaultMaxPerRoute(POOL_MAX_CONN_PER_ROUTE);
			cm.setMaxTotal(POOL_MAX_CONN);
			cm.setDefaultSocketConfig(SocketConfig.copy(SocketConfig.DEFAULT).setTcpNoDelay(true).setSoReuseAddress(true).setSoLinger(0).build());
			// cm.setValidateAfterInactivity(POOL_CONN_INACTIVITY_DURATION);
			connectionManagers.put(key, cm);
		}
		HttpClientBuilder builder = HttpClients.custom();
		builder

				.setConnectionManager(cm)

				.setConnectionManagerShared(true)

				.setConnectionTimeToLive(POOL_CONN_TTL_MS, TimeUnit.MILLISECONDS)

				.setConnectionReuseStrategy(new DefaultClientConnectionReuseStrategy())

				.setRetryHandler(new NoHttpResponseExceptionHttpRequestRetryHandler());

		return builder;
	}

	private static class NoHttpResponseExceptionHttpRequestRetryHandler implements HttpRequestRetryHandler {
		@Override
		public boolean retryRequest(java.io.IOException exception, int executionCount, HttpContext context) {
			if (executionCount <= 2 && exception instanceof org.apache.http.NoHttpResponseException) {
				LogUtil.log(Log.LEVEL_INFO, "http-conn", ExceptionUtil.getStacktrace(exception, true));
				return true;
			}
			return false;
		}
	}

	public static void setTimeout(HttpClientBuilder builder, TimeSpan timeout) {
		if (timeout == null || timeout.getMillis() <= 0) return;

		int ms = (int) timeout.getMillis();
		if (ms < 0) ms = Integer.MAX_VALUE;

		SocketConfig sc = SocketConfig.custom().setSoTimeout(ms).build();
		builder.setDefaultSocketConfig(sc);
	}

	private static Registry<ConnectionSocketFactory> createRegistry() throws GeneralSecurityException {
		SSLContext sslcontext = SSLContext.getInstance("TLS");
		sslcontext.init(null, null, new java.security.SecureRandom());
		SSLConnectionSocketFactory defaultsslsf = new SSLConnectionSocketFactoryImpl(sslcontext, new DefaultHostnameVerifierImpl());
		/* Register connection handlers */
		return RegistryBuilder.<ConnectionSocketFactory>create().register("http", PlainConnectionSocketFactory.getSocketFactory()).register("https", defaultsslsf).build();

	}

	private static Registry<ConnectionSocketFactory> createRegistry(String clientCert, String clientCertPassword)
			throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException, KeyManagementException {
		// Currently, clientCert force usePool to being ignored
		if (clientCertPassword == null) clientCertPassword = "";
		// Load the client cert
		File ksFile = new File(clientCert);
		KeyStore clientStore = KeyStore.getInstance("PKCS12");
		clientStore.load(new FileInputStream(ksFile), clientCertPassword.toCharArray());
		// Prepare the keys
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(clientStore, clientCertPassword.toCharArray());
		SSLContext sslcontext = SSLContext.getInstance("TLS");
		// Configure the socket factory
		sslcontext.init(kmf.getKeyManagers(), null, new java.security.SecureRandom());
		SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactoryImpl(sslcontext, new DefaultHostnameVerifierImpl());
		return RegistryBuilder.<ConnectionSocketFactory>create().register("http", PlainConnectionSocketFactory.getSocketFactory()).register("https", sslsf).build();
	}

	public static void releaseConnectionManager() {
		Collection<PoolingHttpClientConnectionManager> values = connectionManagers.values();
		connectionManagers = new ConcurrentHashMap<String, PoolingHttpClientConnectionManager>();
		for (PoolingHttpClientConnectionManager cm: values) {
			IOUtil.closeEL(cm);
		}
	}

	public static boolean isShutDown(PoolingHttpClientConnectionManager cm, boolean defaultValue) {
		if (cm != null && !cannotAccess) {
			try {
				if (isShutDownField == null || isShutDownField.getDeclaringClass() != cm.getClass()) {
					isShutDownField = cm.getClass().getDeclaredField("isShutDown");
					isShutDownField.setAccessible(true);
				}
				return ((AtomicBoolean) isShutDownField.get(cm)).get();
			}
			catch (Exception e) {
				cannotAccess = true;// depending on JRE used
				LogUtil.log("http", e);
			}
		}
		return defaultValue;
	}

	public static void closeIdleConnections() {
		for (PoolingHttpClientConnectionManager cm: connectionManagers.values()) {
			cm.closeIdleConnections(POOL_CONN_TTL_MS, TimeUnit.MILLISECONDS);
			cm.closeExpiredConnections();
		}
	}

	private static HTTPResponse invoke(URL url, HttpUriRequest request, String username, String password, long timeout, boolean redirect, String charset, String useragent,
			ProxyData proxy, lucee.commons.net.http.Header[] headers, Map<String, String> formfields, boolean pooling) throws IOException, GeneralSecurityException {
		CloseableHttpClient client;
		proxy = ProxyDataImpl.validate(proxy, url.getHost());

		HttpClientBuilder builder = getHttpClientBuilder(pooling, null, null);

		// LDEV-2321
		builder.setDefaultRequestConfig(RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build());

		// redirect
		if (redirect) builder.setRedirectStrategy(DefaultRedirectStrategy.INSTANCE);
		else builder.disableRedirectHandling();

		HttpHost hh = new HttpHost(url.getHost(), url.getPort());
		setHeader(request, headers);
		if (CollectionUtil.isEmpty(formfields)) setContentType(request, charset);
		setFormFields(request, formfields, charset);
		setUserAgent(request, useragent);
		if (timeout > 0) Http.setTimeout(builder, TimeSpanImpl.fromMillis(timeout));
		HttpContext context = setCredentials(builder, hh, username, password, false);
		setProxy(url.getHost(), builder, request, proxy);
		client = builder.build();
		if (context == null) context = new BasicHttpContext();

		return new HTTPResponse4Impl(url, context, request, client.execute(request, context));
	}

	private static void setFormFields(HttpUriRequest request, Map<String, String> formfields, String charset) throws IOException {
		if (!CollectionUtil.isEmpty(formfields)) {
			if (!(request instanceof HttpPost)) throw new IOException("form fields are only suppported for post request");
			HttpPost post = (HttpPost) request;
			List<NameValuePair> list = new ArrayList<NameValuePair>();
			Iterator<Entry<String, String>> it = formfields.entrySet().iterator();
			Entry<String, String> e;
			while (it.hasNext()) {
				e = it.next();
				list.add(new BasicNameValuePair(e.getKey(), e.getValue()));
			}
			if (StringUtil.isEmpty(charset)) charset = ((PageContextImpl) ThreadLocalPageContext.get()).getWebCharset().name();

			post.setEntity(new org.apache.http.client.entity.UrlEncodedFormEntity(list, charset));
		}
	}

	private static void setUserAgent(HttpMessage hm, String useragent) {
		if (useragent != null) hm.setHeader("User-Agent", useragent);
	}

	private static void setContentType(HttpMessage hm, String charset) {
		if (charset != null) hm.setHeader("Content-type", "text/html; charset=" + charset);
	}

	private static void setHeader(HttpMessage hm, lucee.commons.net.http.Header[] headers) {
		addHeader(hm, headers);
	}

	private static void addHeader(HttpMessage hm, lucee.commons.net.http.Header[] headers) {
		if (headers != null) {
			for (int i = 0; i < headers.length; i++)
				hm.addHeader(toHeader(headers[i]));
		}
	}

	public static BasicHttpContext setCredentials(HttpClientBuilder builder, HttpHost httpHost, String username, String password, boolean preAuth) {
		// set Username and Password
		if (!StringUtil.isEmpty(username, true)) {

			if (password == null) password = "";

			CredentialsProvider cp = new BasicCredentialsProvider();
			builder.setDefaultCredentialsProvider(cp);

			cp.setCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT), new UsernamePasswordCredentials(username, password));

			BasicHttpContext httpContext = new BasicHttpContext();
			if (preAuth) {
				AuthCache authCache = new BasicAuthCache();
				authCache.put(httpHost, new BasicScheme());
				httpContext.setAttribute(ClientContext.AUTH_CACHE, authCache);
			}

			return httpContext;
		}

		return null;
	}

	public static void setNTCredentials(HttpClientBuilder builder, String username, String password, String workStation, String domain) {
		// set Username and Password
		if (!StringUtil.isEmpty(username, true)) {
			if (password == null) password = "";
			CredentialsProvider cp = new BasicCredentialsProvider();
			builder.setDefaultCredentialsProvider(cp);

			cp.setCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT), new NTCredentials(username, password, workStation, domain));
		}
	}

	public static void setBody(HttpEntityEnclosingRequest req, Object body, String mimetype, String charset) throws IOException {
		if (body != null) req.setEntity(toHttpEntity(body, mimetype, charset));
	}

	public static void setProxy(String host, HttpClientBuilder builder, HttpUriRequest request, ProxyData proxy) {
		// set Proxy
		if (ProxyDataImpl.isValid(proxy, host)) {
			HttpHost hh = new HttpHost(proxy.getServer(), proxy.getPort() == -1 ? 80 : proxy.getPort());
			builder.setProxy(hh);

			// username/password
			if (!StringUtil.isEmpty(proxy.getUsername())) {
				CredentialsProvider cp = new BasicCredentialsProvider();
				builder.setDefaultCredentialsProvider(cp);
				cp.setCredentials(new AuthScope(proxy.getServer(), proxy.getPort()), new UsernamePasswordCredentials(proxy.getUsername(), proxy.getPassword()));
			}
		}
	}

	public static void addCookie(CookieStore cookieStore, String domain, String name, String value, String path, String charset) {
		if (ReqRspUtil.needEncoding(name, false)) name = ReqRspUtil.encode(name, charset);
		if (ReqRspUtil.needEncoding(value, false)) value = ReqRspUtil.encode(value, charset);
		BasicClientCookie cookie = new BasicClientCookie(name, value);
		if (!StringUtil.isEmpty(domain, true)) cookie.setDomain(domain);
		if (!StringUtil.isEmpty(path, true)) cookie.setPath(path);
		cookieStore.addCookie(cookie);
	}

	/**
	 * convert input to HTTP Entity
	 * 
	 * @param value
	 * @param mimetype not used for binary input
	 * @param charset not used for binary input
	 * @return
	 * @throws IOException
	 */
	private static HttpEntity toHttpEntity(Object value, String mimetype, String charset) throws IOException {
		if (value instanceof HttpEntity) return (HttpEntity) value;

		// content type
		ContentType ct = HTTPEngine.toContentType(mimetype, charset);
		try {
			if (value instanceof TemporaryStream) {
				if (ct != null) return new TemporaryStreamHttpEntity((TemporaryStream) value, ct);
				return new TemporaryStreamHttpEntity((TemporaryStream) value, null);
			}
			else if (value instanceof InputStream) {
				if (ct != null) return new ByteArrayEntity(IOUtil.toBytes((InputStream) value), ct);
				return new ByteArrayEntity(IOUtil.toBytes((InputStream) value));
			}
			else if (Decision.isCastableToBinary(value, false)) {
				if (ct != null) return new ByteArrayEntity(Caster.toBinary(value), ct);
				return new ByteArrayEntity(Caster.toBinary(value));
			}
			else {
				boolean wasNull = false;
				if (ct == null) {
					wasNull = true;
					ct = ContentType.APPLICATION_OCTET_STREAM;
				}
				String str = Caster.toString(value);
				if (str.equals("<empty>")) {
					return new EmptyHttpEntity(ct);
				}
				if (wasNull && !StringUtil.isEmpty(charset, true)) return new StringEntity(str, charset.trim());
				else return new StringEntity(str, ct);
			}
		}
		catch (Exception e) {
			throw ExceptionUtil.toIOException(e);
		}
	}

	public static Entity getEmptyEntity(ContentType contentType) {
		return new EmptyHttpEntity(contentType);
	}

	public static Entity getByteArrayEntity(byte[] barr, ContentType contentType) {
		return new ByteArrayHttpEntity(barr, contentType);
	}

	public static Entity getTemporaryStreamEntity(TemporaryStream ts, ContentType contentType) {
		return new TemporaryStreamHttpEntity(ts, contentType);
	}

	public static Entity getResourceEntity(Resource res, ContentType contentType) {
		return new ResourceHttpEntity(res, contentType);
	}

}