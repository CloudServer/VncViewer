/*
 * SSLSocketToMe.java: add SSL encryption to Java VNC Viewer.
 *
 * Copyright (c) 2006 Karl J. Runge <runge@karlrunge.com>
 * All rights reserved.
 *
 *  This is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 *  USA.
 *
 */
package com.tightvnc.vncviewer;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.CheckboxGroup;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SSLSocketToMe {

	/* basic member data: */
	String host;
	int port;
	VncViewer viewer;
	boolean debug = true;

	/* sockets */
	SSLSocket socket = null;
	SSLSocketFactory factory;

	/* fallback for Proxy connection */
	boolean proxy_in_use = false;
	boolean proxy_is_https = false;
	boolean proxy_failure = false;
	public DataInputStream is = null;
	public OutputStream os = null;

	String proxy_auth_string = null;
	String proxy_dialog_host = null;
	int proxy_dialog_port = 0;

	Socket proxySock;
	DataInputStream proxy_is;
	OutputStream proxy_os;

	/* trust contexts */
	SSLContext trustloc_ctx;
	SSLContext trustall_ctx;
	SSLContext trusturl_ctx;
	SSLContext trustone_ctx;

	TrustManager[] trustAllCerts;
	TrustManager[] trustUrlCert;
	TrustManager[] trustOneCert;

	boolean use_url_cert_for_auth = true;
	boolean user_wants_to_see_cert = true;

	/* cert(s) we retrieve from VNC server */
	java.security.cert.Certificate[] trustallCerts = null;
	java.security.cert.Certificate[] trusturlCerts = null;

	byte[] hex2bytes(String s) {
		byte[] bytes = new byte[s.length()/2];
		for (int i=0; i<s.length()/2; i++) {
			int j = 2*i;
			try {
				int val = Integer.parseInt(s.substring(j, j+2), 16);
				if (val > 127) {
					val -= 256;
				}
				Integer I = new Integer(val);
				bytes[i] = Byte.decode(I.toString()).byteValue();
				
			} catch (Exception e) {
				;
			}
		}
		return bytes;
	}

	SSLSocketToMe(String h, int p, VncViewer v) throws Exception {
		host = h;
		port = p;
		viewer = v;

		/* we will first try default factory for certification: */

		factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

		dbg("SSL startup: " + host + " " + port);
		/* create trust managers used if initial handshake fails: */

		trustAllCerts = new TrustManager[] {
		    /*
		     * this one accepts everything.
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) {
				/* empty */
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) {
				/* empty */
				dbg("ALL: an untrusted connect to grab cert.");
			}
		    }
		};

		trustUrlCert = new TrustManager[] {
		    /*
		     * this one accepts only the retrieved server cert
		     * by SSLSocket by this applet.
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				throw new CertificateException("No Clients");
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
			  
			  if (true)
			    return;
			  
				if (trusturlCerts == null) {
					throw new CertificateException(
					    "No Trust url Certs array.");
				}
				if (trusturlCerts.length < 1) {
					throw new CertificateException(
					    "No Trust url Certs.");
				}
				if (trusturlCerts.length > 1) {
					int i;
					boolean ok = true;
					for (i = 0; i < trusturlCerts.length - 1; i++)  {
						if (! trusturlCerts[i].equals(trusturlCerts[i+1])) {
							ok = false;
						}
					}
					if (! ok) {
						throw new CertificateException(
						    "Too many Trust url Certs: "
						    + trusturlCerts.length
						);
					}
				}
				if (certs == null) {
					throw new CertificateException(
					    "No this-certs array.");
				}
				if (certs.length < 1) {
					throw new CertificateException(
					    "No this-certs Certs.");
				}
				if (certs.length > 1) {
					int i;
					boolean ok = true;
					for (i = 0; i < certs.length - 1; i++)  {
						if (! certs[i].equals(certs[i+1])) {
							ok = false;
						}
					}
					if (! ok) {
						throw new CertificateException(
						    "Too many this-certs: "
						    + certs.length
						);
					}
				}
				if (! trusturlCerts[0].equals(certs[0])) {
					throw new CertificateException(
					    "Server Cert Changed != URL.");
				}
				dbg("URL: trusturlCerts[0] matches certs[0]");
			}
		    }
		};
		trustOneCert = new TrustManager[] {
		    /*
		     * this one accepts only the retrieved server cert
		     * by SSLSocket by this applet.
		     */
		    new X509TrustManager() {
			public java.security.cert.X509Certificate[]
			    getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
				throw new CertificateException("No Clients");
			}
			public void checkServerTrusted(
			    java.security.cert.X509Certificate[] certs,
			    String authType) throws CertificateException {
			  
			  if (true)
			    return;
			  
				if (trustallCerts == null) {
					throw new CertificateException(
					    "No Trust All Server Certs array.");
				}
				if (trustallCerts.length < 1) {
					throw new CertificateException(
					    "No Trust All Server Certs.");
				}
				if (trustallCerts.length > 1) {
					int i;
					boolean ok = true;
					for (i = 0; i < trustallCerts.length - 1; i++)  {
						if (! trustallCerts[i].equals(trustallCerts[i+1])) {
							ok = false;
						}
					}
					if (! ok) {
						throw new CertificateException(
						    "Too many Trust All Server Certs: "
						    + trustallCerts.length
						);
					}
				}
				if (certs == null) {
					throw new CertificateException(
					    "No this-certs array.");
				}
				if (certs.length < 1) {
					throw new CertificateException(
					    "No this-certs Certs.");
				}
				if (certs.length > 1) {
					int i;
					boolean ok = true;
					for (i = 0; i < certs.length - 1; i++)  {
						if (! certs[i].equals(certs[i+1])) {
							ok = false;
						}
					}
					if (! ok) {
						throw new CertificateException(
						    "Too many this-certs: "
						    + certs.length
						);
					}
				}
				if (! trustallCerts[0].equals(certs[0])) {
					throw new CertificateException(
					    "Server Cert Changed != TRUSTALL.");
				}
				dbg("ONE: trustallCerts[0] matches certs[0]");
			}
		    }
		};

		/* 
		 * They are used:
		 *
		 * 1) to retrieve the server cert in case of failure to
		 *    display it to the user.
		 * 2) to subsequently connect to the server if user agrees.
		 */
/*
		KeyManager[] mykey = null;
		if (viewer.oneTimeKey != null && viewer.oneTimeKey.equals("PROMPT")) {
			ClientCertDialog d = new ClientCertDialog();
			viewer.oneTimeKey = d.queryUser();
		}

		if (viewer.oneTimeKey != null && viewer.oneTimeKey.indexOf(",") > 0) {
			int idx = viewer.oneTimeKey.indexOf(",");

			String onetimekey = viewer.oneTimeKey.substring(0, idx);
			byte[] key = hex2bytes(onetimekey);
			String onetimecert = viewer.oneTimeKey.substring(idx+1);
			byte[] cert = hex2bytes(onetimecert);

			KeyFactory kf = KeyFactory.getInstance("RSA");
			PKCS8EncodedKeySpec keysp = new PKCS8EncodedKeySpec ( key );
			PrivateKey ff = kf.generatePrivate (keysp);
			//dbg("ff " + ff);
			String cert_str = new String(cert);

			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			Collection c = cf.generateCertificates(new ByteArrayInputStream(cert));
			Certificate[] certs = new Certificate[c.toArray().length];
			if (c.size() == 1) {
				Certificate tmpcert = cf.generateCertificate(new ByteArrayInputStream(cert));
				//dbg("tmpcert" + tmpcert);
				certs[0] = tmpcert;
			} else {
				certs = (Certificate[]) c.toArray();
			}

			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, null);
			ks.setKeyEntry("onetimekey", ff, "".toCharArray(), certs);
			String da = KeyManagerFactory.getDefaultAlgorithm();
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(da);
			kmf.init(ks, "".toCharArray());

			mykey = kmf.getKeyManagers();
		}
*/
		/* trust loc certs: */
		try {
			trustloc_ctx = SSLContext.getInstance("SSL");
/*
			trustloc_ctx.init(mykey, null, new
			    java.security.SecureRandom());
*/
			trustloc_ctx.init(null, null, new
				    java.security.SecureRandom());
		} catch (Exception e) {
			String msg = "SSL trustloc_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* trust all certs: */
		try {
			trustall_ctx = SSLContext.getInstance("SSL");
/*
			trustall_ctx.init(mykey, trustAllCerts, new
			    java.security.SecureRandom());
*/
			trustall_ctx.init(null, trustAllCerts, new
				    java.security.SecureRandom());
		} catch (Exception e) {
			String msg = "SSL trustall_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* trust url certs: */
		try {
			trusturl_ctx = SSLContext.getInstance("SSL");
/*
			trusturl_ctx.init(mykey, trustUrlCert, new
			    java.security.SecureRandom());
*/
			trusturl_ctx.init(null, trustUrlCert, new
				    java.security.SecureRandom());
		} catch (Exception e) {
			String msg = "SSL trusturl_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}

		/* trust the one cert from server: */
		try {
			trustone_ctx = SSLContext.getInstance("SSL");
/*
			trustone_ctx.init(mykey, trustOneCert, new
			    java.security.SecureRandom());
*/
			trustone_ctx.init(null, trustOneCert, new
				    java.security.SecureRandom());
		} catch (Exception e) {
			String msg = "SSL trustone_ctx FAILED.";
			dbg(msg);
			throw new Exception(msg);
		}
	}

	boolean browser_cert_match() {
		String msg = "Browser URL accept previously accepted cert";

		if (user_wants_to_see_cert) {
			return false;
		}

		if (trustallCerts != null && trusturlCerts != null) {
		    if (trustallCerts.length == 1 && trusturlCerts.length == 1) {
			if (trustallCerts[0].equals(trusturlCerts[0])) {
				System.out.println(msg);
				return true;
			}
		    }
		}
		return false;
	}
/*
	public void check_for_proxy() {
		
		boolean result = false;

		trusturlCerts = null;
		proxy_in_use = false;
		if (viewer.ignoreProxy) {
			return;
		}

		String ustr = "https://" + host + ":";
		if (viewer.httpsPort != null) {
			ustr += viewer.httpsPort;
		} else {
			ustr += port;	// hmmm
		}
		ustr += viewer.urlPrefix + "/check.https.proxy.connection";
		dbg("ustr is: " + ustr);


		try {
			URL url = new URL(ustr);
			HttpsURLConnection https = (HttpsURLConnection)
			    url.openConnection();

			https.setUseCaches(false);
			https.setRequestMethod("GET");
			https.setRequestProperty("Pragma", "No-Cache");
			https.setRequestProperty("Proxy-Connection",
			    "Keep-Alive");
			https.setDoInput(true);

			https.connect();

			trusturlCerts = https.getServerCertificates();
			if (trusturlCerts == null) {
				dbg("set trusturlCerts to null...");
			} else {
				dbg("set trusturlCerts to non-null");
			}

			if (https.usingProxy()) {
				proxy_in_use = true;
				proxy_is_https = true;
				dbg("HTTPS proxy in use. There may be connection problems.");
			}
			Object output = https.getContent();
			https.disconnect();
			result = true;

		} catch(Exception e) {
			dbg("HttpsURLConnection: " + e.getMessage());
		}

		if (proxy_in_use) {
			return;
		}

		ustr = "http://" + host + ":" + port;
		ustr += viewer.urlPrefix + "/index.vnc";

		try {
			URL url = new URL(ustr);
			HttpURLConnection http = (HttpURLConnection)
			    url.openConnection();

			http.setUseCaches(false);
			http.setRequestMethod("GET");
			http.setRequestProperty("Pragma", "No-Cache");
			http.setRequestProperty("Proxy-Connection",
			    "Keep-Alive");
			http.setDoInput(true);

			http.connect();

			if (http.usingProxy()) {
				proxy_in_use = true;
				proxy_is_https = false;
				dbg("HTTP proxy in use. There may be connection problems.");
			}
			Object output = http.getContent();
			http.disconnect();

		} catch(Exception e) {
			dbg("HttpURLConnection: " + e.getMessage());
		}
	}
*/
	public Socket connectSock() throws IOException {

		/*
		 * first try a https connection to detect a proxy, and
		 * also grab the VNC server cert.
		 */
		/*
		check_for_proxy();
		*/
		if (viewer.trustAllVncCerts) {
			dbg("viewer.trustAllVncCerts-0 using trustall_ctx");
			factory = trustall_ctx.getSocketFactory();
		} else if (use_url_cert_for_auth && trusturlCerts != null) {
			dbg("using trusturl_ctx");
			factory = trusturl_ctx.getSocketFactory();
		} else {
			dbg("using trustloc_ctx");
			factory = trustloc_ctx.getSocketFactory();
		}

		socket = null;
		try {
/*
			if (proxy_in_use && viewer.forceProxy) {
				throw new Exception("forcing proxy (forceProxy)");
			} else if (viewer.CONNECT != null) {
				throw new Exception("forcing CONNECT");
			}
*/
			int timeout = 6;
			if (timeout > 0) {
				socket = (SSLSocket) factory.createSocket();
//				ArrayList<String> a = new ArrayList<String>();
//				for (String c: socket.getSupportedCipherSuites())
//				{
//					//if (c.contains("_anon_"))
//					//if (c.contains("TLS_RSA_WITH_AES_256_CBC_SHA"))
//						a.add(c);
//				}
//				socket.setEnabledCipherSuites(a.toArray(new String[]{}));
				InetSocketAddress inetaddr = new InetSocketAddress(host, port);
				dbg("Using timeout of " + timeout + " secs to: " + host + ":" + port);
				socket.connect(inetaddr, timeout * 1000);
			} else {
				socket = (SSLSocket) factory.createSocket(host, port);
			}

		} catch (Exception esock) {
			dbg("esock: " + esock.getMessage());
/*
			if (proxy_in_use || viewer.CONNECT != null) {
				proxy_failure = true;
				if (proxy_in_use) {
					dbg("HTTPS proxy in use. Trying to go with it.");
				} else {
					dbg("viewer.CONNECT reverse proxy in use. Trying to go with it.");
				}
				try {
					socket = proxy_socket(factory);
				} catch (Exception e) {
					dbg("err proxy_socket: " + e.getMessage());
				}
			}
*/
		}

		try {
			socket.startHandshake();
			dbg("Server Connection Verified on 1st try.");

			if (viewer.trustAllVncCerts) {
				dbg("viewer.trustAllVncCerts-1");
				dbg("viewer.trustAllVncCerts-2");
				user_wants_to_see_cert = false;
			} else {
				SSLSession sess = socket.getSession();
				java.security.cert.Certificate[] currentTrustedCerts = sess.getPeerCertificates();
				if (currentTrustedCerts == null || currentTrustedCerts.length < 1) {
					socket.close();
					socket = null;
					throw new SSLHandshakeException("no current certs");
				}
				String serv = "";
				try {
					CertInfo ci = new CertInfo(currentTrustedCerts[0]);
					serv = ci.get_certinfo("CN");
				} catch (Exception e) {
					;
				}
				BrowserCertsDialog bcd = new BrowserCertsDialog(serv, host + ":" + port);
				dbg("browser certs dialog START");
				bcd.queryUser();
				dbg("browser certs dialog DONE");
				user_wants_to_see_cert = false;
//				if (bcd.showCertDialog) {
//					String msg = "user wants to see cert";
//					dbg(msg);
//					user_wants_to_see_cert = true;
//					throw new SSLHandshakeException(msg);
//				} else {
//					user_wants_to_see_cert = false;
//					dbg("browser certs dialog: user said yes, accept it");
//				}
			}
		} catch (SSLHandshakeException eh)  {
			dbg("Could not automatically verify Server.");
			dbg("msg: " + eh.getMessage());
			String getoutstr = "GET /index.vnc HTTP/1.0\r\nConnection: close\r\n\r\n";

    			OutputStream os = socket.getOutputStream();
			os.write(getoutstr.getBytes());
			socket.close();
			socket = null;

			/*
			 * Reconnect, trusting any cert, so we can grab
			 * the cert to show it to the user.  The connection
			 * is not used for anything else.
			 */
			factory = trustall_ctx.getSocketFactory();
			if (proxy_failure) {
				socket = proxy_socket(factory);
			} else {
				socket = (SSLSocket) factory.createSocket(host, port);
			}

			try {
				socket.startHandshake();
				dbg("TrustAll Server Connection Verified.");

				/* grab the cert: */
				try {
					SSLSession sess = socket.getSession();
					trustallCerts = sess.getPeerCertificates();
				} catch (Exception e) {
					throw new Exception("Could not get " + 
					    "Peer Certificate");	
				}

				if (viewer.trustAllVncCerts) {
					dbg("viewer.trustAllVncCerts-3");
				} else if (! browser_cert_match()) {
					/*
					 * close socket now, we will reopen after
					 * dialog if user agrees to use the cert.
					 */
    					os = socket.getOutputStream();
					os.write(getoutstr.getBytes());
					socket.close();
					socket = null;

					/* dialog with user to accept cert or not: */

					TrustDialog td= new TrustDialog(host, port,
					    trustallCerts);

					if (! td.queryUser()) {
						String msg = "User decided against it.";
						dbg(msg);
						throw new IOException(msg);
					}
				}

			} catch (Exception ehand2)  {
				dbg("** Could not TrustAll Verify Server.");

				throw new IOException(ehand2.getMessage());
			}

			if (socket != null) {
				try {
					socket.close();
				} catch (Exception e) {
					;
				}
				socket = null;
			}

			/*
			 * Now connect a 3rd time, using the cert
			 * retrieved during connection 2 (that the user
			 * likely blindly agreed to).
			 */

			factory = trustone_ctx.getSocketFactory();
			if (proxy_failure) {
				socket = proxy_socket(factory);
			} else {
				socket = (SSLSocket) factory.createSocket(host, port);
			}

			try {
				socket.startHandshake();
				dbg("TrustAll Server Connection Verified #3.");

			} catch (Exception ehand3)  {
				dbg("** Could not TrustAll Verify Server #3.");

				throw new IOException(ehand3.getMessage());
			}
		}
/*
		if (socket != null && viewer.GET) {
			String str = "GET ";
			str += viewer.urlPrefix;
			str += "/request.https.vnc.connection";
			str += " HTTP/1.0\r\n";
			str += "Pragma: No-Cache\r\n";
			str += "\r\n";
			System.out.println("sending GET: " + str);
    			OutputStream os = socket.getOutputStream();
			String type = "os";
			if (type == "os") {
				os.write(str.getBytes());
				os.flush();
				System.out.println("used OutputStream");
			} else if (type == "bs") {
				BufferedOutputStream bs = new BufferedOutputStream(os);
				bs.write(str.getBytes());
				bs.flush();
				System.out.println("used BufferedOutputStream");
			} else if (type == "ds") {
				DataOutputStream ds = new DataOutputStream(os);
				ds.write(str.getBytes());
				ds.flush();
				System.out.println("used DataOutputStream");
			}
			if (false) {
				String rep = "";
				DataInputStream is = new DataInputStream(
				    new BufferedInputStream(socket.getInputStream(), 16384));
				while (true) {
					rep += readline(is);
					if (rep.indexOf("\r\n\r\n") >= 0) {
						break;
					}
				}
				System.out.println("rep: " + rep);
			}
		}
*/
		dbg("SSL returning socket to caller.");
		return (Socket) socket;
	}

	private void dbg(String s) {
		if (debug) {
			System.out.println(s);
		}
	}

	private int gint(String s) {
		int n = -1;
		try {
			Integer I = new Integer(s);
			n = I.intValue();
		} catch (Exception ex) {
			return -1;
		}
		return n;
	}

	private void proxy_helper(String proxyHost, int proxyPort) {

		boolean proxy_auth = false;
		String proxy_auth_basic_realm = "";
		String hp = host + ":" + port;
		dbg("proxy_helper: " + proxyHost + ":" + proxyPort + " hp: " + hp);

		for (int k=0; k < 2; k++) {
			dbg("proxy_in_use psocket:");

			if (proxySock != null) {
				try {
					proxySock.close();
				} catch (Exception e) {
					;
				}
			}

			proxySock = psocket(proxyHost, proxyPort);
			if (proxySock == null) {
				dbg("1-a sadly, returning a null socket");
				return;
			}

			String req1 = "CONNECT " + hp + " HTTP/1.1\r\n"
			    + "Host: " + hp + "\r\n";

			dbg("requesting: " + req1);

			if (proxy_auth) {
				if (proxy_auth_string == null) {
					ProxyPasswdDialog pp = new ProxyPasswdDialog(proxyHost, proxyPort, proxy_auth_basic_realm);
					pp.queryUser();
					proxy_auth_string = pp.getAuth();
				}
				//dbg("auth1: " + proxy_auth_string);
				String auth2 = Base64Coder.encodeString(proxy_auth_string);
				//dbg("auth2: " + auth2);
				req1 += "Proxy-Authorization: Basic " + auth2 + "\r\n";
				//dbg("req1: " + req1);
				dbg("added Proxy-Authorization: Basic ... to request");
			}
			req1 += "\r\n";

			try {
				proxy_os.write(req1.getBytes());
				String reply = readline(proxy_is);

				dbg("proxy replied: " + reply.trim());

				if (reply.indexOf("HTTP/1.") == 0 && reply.indexOf(" 407 ") > 0) {
					proxy_auth = true;
					proxySock.close();
				} else if (reply.indexOf("HTTP/1.") < 0 && reply.indexOf(" 200") < 0) {
					proxySock.close();
					proxySock = psocket(proxyHost, proxyPort);
					if (proxySock == null) {
						dbg("2-a sadly, returning a null socket");
						return;
					}
				}
			} catch(Exception e) {
				dbg("sock prob: " + e.getMessage());
			}

			while (true) {
				String line = readline(proxy_is);
				dbg("proxy line: " + line.trim());
				if (proxy_auth) {
					String uc = line.toLowerCase();
					if (uc.indexOf("proxy-authenticate:") == 0) {
						if (uc.indexOf(" basic ") >= 0) {
							int idx = uc.indexOf(" realm");
							if (idx >= 0) {
								proxy_auth_basic_realm = uc.substring(idx+1);
							}
						}
					}
				}
				if (line.equals("\r\n") || line.equals("\n")) {
					break;
				}
			}
			if (!proxy_auth || proxy_auth_basic_realm.equals("")) {
				break;
			}
		}
	}

	public SSLSocket proxy_socket(SSLSocketFactory factory) {
		Properties props = null;
		String proxyHost = null;
		int proxyPort = 0;
		String proxyHost_nossl = null;
		int proxyPort_nossl = 0;
		String str;

		/* see if we can guess the proxy info from Properties: */
		try {
			props = System.getProperties();
		} catch (Exception e) {
			dbg("props failed: " + e.getMessage());
		}
/*
		if (viewer.proxyHost != null) {
			dbg("Using supplied proxy " + viewer.proxyHost + " " + viewer.proxyPort + " applet parameters.");
			proxyHost = viewer.proxyHost;
			if (viewer.proxyPort != null) {
				proxyPort = gint(viewer.proxyPort);
			} else {
				proxyPort = 8080;
			}
			
		} else
*/
		if (props != null) {
			dbg("\n---------------\nAll props:");
			props.list(System.out);
			dbg("\n---------------\n\n");

			for (Enumeration e = props.propertyNames(); e.hasMoreElements(); ) {
				String s = (String) e.nextElement();
				String v = System.getProperty(s);
				String s2 = s.toLowerCase();
				String v2 = v.toLowerCase();

				if (s2.indexOf("proxy") < 0 && v2.indexOf("proxy") < 0) {
					continue;
				}
				if (v2.indexOf("http") < 0) {
					continue;
				}

				if (s2.indexOf("proxy.https.host") >= 0) {
					proxyHost = v2;
					continue;
				}
				if (s2.indexOf("proxy.https.port") >= 0) {
					proxyPort = gint(v2);
					continue;
				}
				if (s2.indexOf("proxy.http.host") >= 0) {
					proxyHost_nossl = v2;
					continue;
				}
				if (s2.indexOf("proxy.http.port") >= 0) {
					proxyPort_nossl = gint(v2);
					continue;
				}

				String[] pieces = v.split("[,;]");
				for (int i = 0; i < pieces.length; i++) {
					String p = pieces[i];
					int j = p.indexOf("https");
					if (j < 0) {
						j = p.indexOf("http");
						if (j < 0) {
							continue;
						}
					}
					j = p.indexOf("=", j);
					if (j < 0) {
						continue;
					}
					p = p.substring(j+1);
					String [] hp = p.split(":");
					if (hp.length != 2) {
						continue;
					}
					if (hp[0].length() > 1 && hp[1].length() > 1) {

						proxyPort = gint(hp[1]);
						if (proxyPort < 0) {
							continue;
						}
						proxyHost = new String(hp[0]);
						break;
					}
				}
			}
		}
		if (proxyHost != null) {
			if (proxyHost_nossl != null && proxyPort_nossl > 0) {
				dbg("Using http proxy info instead of https.");
				proxyHost = proxyHost_nossl;
				proxyPort = proxyPort_nossl;
			}
		}

		if (proxy_in_use) {
			if (proxy_dialog_host != null && proxy_dialog_port > 0) {
				proxyHost = proxy_dialog_host;
				proxyPort = proxy_dialog_port;
			}
			if (proxyHost != null) {
				dbg("Lucky us! we figured out the Proxy parameters: " + proxyHost + " " + proxyPort);
			} else {
				/* ask user to help us: */
				ProxyDialog pd = new ProxyDialog(proxyHost, proxyPort);
				pd.queryUser();
				proxyHost = pd.getHost(); 
				proxyPort = pd.getPort();
				proxy_dialog_host = new String(proxyHost);
				proxy_dialog_port = proxyPort;
				dbg("User said host: " + pd.getHost() + " port: " + pd.getPort());
			}

			proxy_helper(proxyHost, proxyPort);
			if (proxySock == null) {
				return null;
			}
		}
/*
		else if (viewer.CONNECT != null) {
			dbg("viewer.CONNECT psocket:");
			proxySock = psocket(host, port);
			if (proxySock == null) {
				dbg("1-b sadly, returning a null socket");
				return null;
			}
		}
		
		if (viewer.CONNECT != null) {
			String hp = viewer.CONNECT;
			String req2 = "CONNECT " + hp + " HTTP/1.1\r\n"
			    + "Host: " + hp + "\r\n\r\n";

			dbg("requesting2: " + req2);

			try {
				proxy_os.write(req2.getBytes());
				String reply = readline(proxy_is);

				dbg("proxy replied2: " + reply.trim());

				if (reply.indexOf("HTTP/1.") < 0 && reply.indexOf(" 200") < 0) {
					proxySock.close();
					proxySock = psocket(proxyHost, proxyPort);
					if (proxySock == null) {
						dbg("2-b sadly, returning a null socket");
						return null;
					}
				}
			} catch(Exception e) {
				dbg("sock prob2: " + e.getMessage());
			}

			while (true) {
				String line = readline(proxy_is);
				dbg("proxy line2: " + line.trim());
				if (line.equals("\r\n") || line.equals("\n")) {
					break;
				}
			}
		}
*/
		Socket sslsock = null;
		try {
			sslsock = factory.createSocket(proxySock, host, port, true);
		} catch(Exception e) {
			dbg("sslsock prob: " + e.getMessage());
			dbg("3 sadly, returning a null socket");
		}

		return (SSLSocket) sslsock;
	}

	Socket psocket(String h, int p) {
		Socket psock = null;
		try {
			psock = new Socket(h, p);
			proxy_is = new DataInputStream(new BufferedInputStream(
			    psock.getInputStream(), 16384));
			proxy_os = psock.getOutputStream();
		} catch(Exception e) {
			dbg("psocket prob: " + e.getMessage());
			return null;
		}

		return psock;
	}

	String readline(DataInputStream i) {
		byte[] ba = new byte[1];
		String s = new String("");
		ba[0] = 0;
		try {
			while (ba[0] != 0xa) {
				ba[0] = (byte) i.readUnsignedByte();
				s += new String(ba);
			}
		} catch (Exception e) {
			;
		}
		return s;
	}
}

class TrustDialog implements ActionListener {
	String msg, host, text;
	int port;
	java.security.cert.Certificate[] trustallCerts = null;
	boolean viewing_cert = false;
	boolean trust_this_session = false;

	/*
	 * this is the gui to show the user the cert and info and ask
	 * them if they want to continue using this cert.
	 */

	Button ok, cancel, viewcert;
	TextArea textarea;
	Checkbox accept, deny;
	Dialog dialog;

	String s1 = "Accept this certificate temporarily for this session";
	String s2 = "Do not accept this certificate and do not connect to"
	    + " this VNC server";
	String ln = "\n---------------------------------------------------\n\n";
		
	TrustDialog (String h, int p, java.security.cert.Certificate[] s) {
		host = h;
		port = p;
		trustallCerts = s;

		msg = "VNC Server " + host + ":" + port + " Not Verified";
	}

	public boolean queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame(msg);

		dialog = new Dialog(frame, true);

		String infostr = "";
		if (trustallCerts.length == 1) {
			CertInfo ci = new CertInfo(trustallCerts[0]);
			infostr = ci.get_certinfo("all");
		}

		text = "\n" 
+ "Unable to verify the identity of\n"
+ "\n"
+ "        " + host + ":" + port + "\n" 
+ "\n"
+ infostr
+ "\n"
+ "as a trusted VNC server.\n"
+ "\n"
+ "This may be due to:\n"
+ "\n"
+ " - Your requesting to View the Certificate before accepting.\n"
+ "\n"
+ " - The VNC server using a Self-Signed Certificate.\n"
+ "\n"
+ " - The VNC server using a Certificate Authority not recognized by your\n"
+ "   Browser or Java Plugin runtime.\n"
+ "\n"
+ " - The use of an Apache SSL portal employing CONNECT proxying and the\n"
+ "   Apache web server has a certificate different from the VNC server's. \n"
+ "\n"
+ " - A Man-In-The-Middle attack impersonating as the VNC server you wish\n"
+ "   to connect to.  (Wouldn't that be exciting!!)\n"
+ "\n"
+ "By safely copying the VNC server's Certificate (or using a common\n"
+ "Certificate Authority certificate) you can configure your Web Browser or\n"
+ "Java Plugin to automatically authenticate this Server.\n"
+ "\n"
+ "If you do so, then you will only have to click \"Yes\" when this VNC\n"
+ "Viewer applet asks you whether to trust your Browser/Java Plugin's\n"
+ "acceptance of the certificate. (except for the Apache portal case above.)\n"
;

		/* the accept / do-not-accept radio buttons: */
		CheckboxGroup checkbox = new CheckboxGroup();
		accept = new Checkbox(s1, true, checkbox);
		deny   = new Checkbox(s2, false, checkbox);

		/* put the checkboxes in a panel: */
		Panel check = new Panel();
		check.setLayout(new GridLayout(2, 1));

		check.add(accept);
		check.add(deny);

		/* make the 3 buttons: */
		ok = new Button("OK");
		cancel = new Button("Cancel");
		viewcert = new Button("View Certificate");

		ok.addActionListener(this);
		cancel.addActionListener(this);
		viewcert.addActionListener(this);

		/* put the buttons in their own panel: */
		Panel buttonrow = new Panel();
		buttonrow.setLayout(new FlowLayout(FlowLayout.LEFT));
		buttonrow.add(viewcert);
		buttonrow.add(ok);
		buttonrow.add(cancel);

		/* label at the top: */
		Label label = new Label(msg, Label.CENTER);
		label.setFont(new Font("Helvetica", Font.BOLD, 16));

		/* textarea in the middle */
		textarea = new TextArea(text, 36, 64,
		    TextArea.SCROLLBARS_VERTICAL_ONLY);
		textarea.setEditable(false);

		/* put the two panels in their own panel at bottom: */
		Panel bot = new Panel();
		bot.setLayout(new GridLayout(2, 1));
		bot.add(check);
		bot.add(buttonrow);

		/* now arrange things inside the dialog: */
		dialog.setLayout(new BorderLayout());

		dialog.add("North", label);
		dialog.add("South", bot);
		dialog.add("Center", textarea);

		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */

		return trust_this_session;
	}

	public synchronized void actionPerformed(ActionEvent evt) {

		if (evt.getSource() == viewcert) {
			/* View Certificate button clicked */
			if (viewing_cert) {
				/* show the original info text: */
				textarea.setText(text);
				viewcert.setLabel("View Certificate");
				viewing_cert = false;
			} else {
				int i;
				/* show all (likely just one) certs: */
				textarea.setText("");
				for (i=0; i < trustallCerts.length; i++) {
					int j = i + 1;
					textarea.append("Certificate[" +
					    j + "]\n\n");
					textarea.append(
					    trustallCerts[i].toString());
					textarea.append(ln);
				}
				viewcert.setLabel("View Info");
				viewing_cert = true;

				textarea.setCaretPosition(0);
			}

		} else if (evt.getSource() == ok) {
			/* OK button clicked */
			if (accept.getState()) {
				trust_this_session = true;
			} else {
				trust_this_session = false;
			}
			//dialog.dispose();
			dialog.hide();

		} else if (evt.getSource() == cancel) {
			/* Cancel button clicked */
			trust_this_session = false;

			//dialog.dispose();
			dialog.hide();
		}
	}

	String get_certinfo() {
		String all = "";
		String fields[] = {"CN", "OU", "O", "L", "C"};
		int i;
		if (trustallCerts.length < 1) {
			all = "";
			return all;
		}
		String cert = trustallCerts[0].toString();

		/*
		 * For now we simply scrape the cert string, there must
		 * be an API for this... perhaps optionValue?
		 */

		for (i=0; i < fields.length; i++) {
			int f, t, t1, t2;
			String sub, mat = fields[i] + "=";
			
			f = cert.indexOf(mat, 0);
			if (f > 0) {
				t1 = cert.indexOf(", ", f);
				t2 = cert.indexOf("\n", f);
				if (t1 < 0 && t2 < 0) {
					continue;
				} else if (t1 < 0) {
					t = t2;
				} else if (t2 < 0) {
					t = t1;
				} else if (t1 < t2) {
					t = t1;
				} else {
					t = t2;
				}
				if (t > f) {
					sub = cert.substring(f, t);
					all = all + "        " + sub + "\n";
				}
			}
		}
		return all;
	}
}

class ProxyDialog implements ActionListener {
	String guessedHost = null;
	String guessedPort = null;
	/*
	 * this is the gui to show the user the cert and info and ask
	 * them if they want to continue using this cert.
	 */

	Button ok;
	Dialog dialog;
	TextField entry;
	String reply = "";

	ProxyDialog (String h, int p) {
		guessedHost = h;
		try {
			guessedPort = Integer.toString(p);
		} catch (Exception e) {
			guessedPort = "8080";
		}
	}

	public void queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Need Proxy host:port");

		dialog = new Dialog(frame, true);


		Label label = new Label("Please Enter your https Proxy info as host:port", Label.CENTER);
		//label.setFont(new Font("Helvetica", Font.BOLD, 16));
		entry = new TextField(30);
		ok = new Button("OK");
		ok.addActionListener(this);

		String guess = "";
		if (guessedHost != null) {
			guess = guessedHost + ":" + guessedPort;
		}
		entry.setText(guess);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", label);
		dialog.add("Center", entry);
		dialog.add("South", ok);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */
		return;
	}

	public String getHost() {
		int i = reply.indexOf(":");
		if (i < 0) {
			return "unknown";
		}
		String h = reply.substring(0, i);
		return h;
	}

	public int getPort() {
		int i = reply.indexOf(":");
		int p = 8080;
		if (i < 0) {
			return p;
		}
		i++;
		String ps = reply.substring(i);
		try {
			Integer I = new Integer(ps);
			p = I.intValue();
		} catch (Exception e) {
			;
		}
		return p;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == ok) {
			reply = entry.getText();
			//dialog.dispose();
			dialog.hide();
		}
	}
}

class ProxyPasswdDialog implements ActionListener {
	String guessedHost = null;
	String guessedPort = null;
	String guessedUser = null;
	String guessedPasswd = null;
	String realm = null;
	/*
	 * this is the gui to show the user the cert and info and ask
	 * them if they want to continue using this cert.
	 */

	Button ok;
	Dialog dialog;
	TextField entry1;
	TextField entry2;
	String reply1 = "";
	String reply2 = "";

	ProxyPasswdDialog (String h, int p, String realm) {
		guessedHost = h;
		try {
			guessedPort = Integer.toString(p);
		} catch (Exception e) {
			guessedPort = "8080";
		}
		this.realm = realm;
	}

	public void queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Proxy Requires Username and Password");

		dialog = new Dialog(frame, true);

		//Label label = new Label("Please Enter your Web Proxy Username in the top Entry and Password in the bottom Entry", Label.CENTER);
		TextArea label = new TextArea("Please Enter your Web Proxy\nUsername in the Top Entry and\nPassword in the Bottom Entry,\nand then press OK.", 4, 20, TextArea.SCROLLBARS_NONE);
		entry1 = new TextField(30);
		entry2 = new TextField(30);
		entry2.setEchoChar('*');
		ok = new Button("OK");
		ok.addActionListener(this);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", label);
		dialog.add("Center", entry1);
		dialog.add("South",  entry2);
		dialog.add("East", ok);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */
		return;
	}

	public String getAuth() {
		return reply1 + ":" + reply2;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == ok) {
			reply1 = entry1.getText();
			reply2 = entry2.getText();
			//dialog.dispose();
			dialog.hide();
		}
	}
}

class ClientCertDialog implements ActionListener {

	Button ok;
	Dialog dialog;
	TextField entry;
	String reply = "";

	ClientCertDialog() {
		;
	}

	public String queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Enter SSL Client Cert+Key String");

		dialog = new Dialog(frame, true);


		Label label = new Label("Please Enter the SSL Client Cert+Key String 308204c0...,...522d2d0a", Label.CENTER);
		entry = new TextField(30);
		ok = new Button("OK");
		ok.addActionListener(this);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", label);
		dialog.add("Center", entry);
		dialog.add("South", ok);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til OK or Cancel pressed. */
		return reply;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == ok) {
			reply = entry.getText();
			//dialog.dispose();
			dialog.hide();
		}
	}
}

class BrowserCertsDialog implements ActionListener {
	Button yes, no;
	Dialog dialog;
	String vncServer;
	String hostport;
	public boolean showCertDialog = true;

	BrowserCertsDialog(String serv, String hp) {
		vncServer = serv;
		hostport = hp;
	}

	public void queryUser() {

		/* create and display the dialog for unverified cert. */

		Frame frame = new Frame("Use Browser/JVM Certs?");

		dialog = new Dialog(frame, true);

		String m = "";
m += "\n";
m += "This VNC Viewer applet does not have its own keystore to track\n";
m += "SSL certificates, and so cannot authenticate the certificate\n";
m += "of the VNC Server:\n";
m += "\n";
m += "        " + hostport + "\n\n        " + vncServer + "\n";
m += "\n";
m += "on its own.\n";
m += "\n";
m += "However, it has noticed that your Web Browser or Java VM Plugin\n";
m += "has previously accepted the same certificate.  You may have set\n";
m += "this up permanently or just for this session, or the server\n";
m += "certificate was signed by a CA cert that your Web Browser or\n";
m += "Java VM Plugin has.\n";
m += "\n";
m += "Should this VNC Viewer applet now connect to the above VNC server?\n";
m += "\n";

//		String m = "\nShould this VNC Viewer applet use your Browser/JVM certs to\n";
//		m += "authenticate the VNC Server:\n";
//		m += "\n        " + hostport + "\n\n        " + vncServer + "\n\n";    
//		m += "(NOTE: this *includes* any certs you have Just Now accepted in a\n";
//		m += "dialog box with your Web Browser or Java Applet Plugin)\n\n";

		TextArea textarea = new TextArea(m, 20, 64,
		    TextArea.SCROLLBARS_VERTICAL_ONLY);
		textarea.setEditable(false);
		yes = new Button("Yes");
		yes.addActionListener(this);
		no = new Button("No, Let Me See the Certificate.");
		no.addActionListener(this);

		dialog.setLayout(new BorderLayout());
		dialog.add("North", textarea);
		dialog.add("Center", yes);
		dialog.add("South", no);
		dialog.pack();
		dialog.resize(dialog.preferredSize());

		dialog.show();	/* block here til Yes or No pressed. */
		System.out.println("done show()");
		return;
	}

	public synchronized void actionPerformed(ActionEvent evt) {
		System.out.println(evt.getActionCommand());
		if (evt.getSource() == yes) {
			showCertDialog = false;
			//dialog.dispose();
			dialog.hide();
		} else if (evt.getSource() == no) {
			showCertDialog = true;
			//dialog.dispose();
			dialog.hide();
		}
		System.out.println("done actionPerformed()");
	}
}

class CertInfo {
	String fields[] = {"CN", "OU", "O", "L", "C"};
	java.security.cert.Certificate cert;
	String certString = "";

	CertInfo(java.security.cert.Certificate c) {
		cert = c;
		certString = cert.toString();
	}
	
	String get_certinfo(String which) {
		int i;
		String cs = new String(certString);
		String all = "";

		/*
		 * For now we simply scrape the cert string, there must
		 * be an API for this... perhaps optionValue?
		 */
		for (i=0; i < fields.length; i++) {
			int f, t, t1, t2;
			String sub, mat = fields[i] + "=";
			
			f = cs.indexOf(mat, 0);
			if (f > 0) {
				t1 = cs.indexOf(", ", f);
				t2 = cs.indexOf("\n", f);
				if (t1 < 0 && t2 < 0) {
					continue;
				} else if (t1 < 0) {
					t = t2;
				} else if (t2 < 0) {
					t = t1;
				} else if (t1 < t2) {
					t = t1;
				} else {
					t = t2;
				}
				if (t > f) {
					sub = cs.substring(f, t);
					all = all + "        " + sub + "\n";
					if (which.equals(fields[i])) {
						return sub;
					}
				}
			}
		}
		if (which.equals("all")) {
			return all;
		} else {
			return "";
		}
	}
}

class Base64Coder {

	// Mapping table from 6-bit nibbles to Base64 characters.
	private static char[]    map1 = new char[64];
	   static {
	      int i=0;
	      for (char c='A'; c<='Z'; c++) map1[i++] = c;
	      for (char c='a'; c<='z'; c++) map1[i++] = c;
	      for (char c='0'; c<='9'; c++) map1[i++] = c;
	      map1[i++] = '+'; map1[i++] = '/'; }

	// Mapping table from Base64 characters to 6-bit nibbles.
	private static byte[]    map2 = new byte[128];
	   static {
	      for (int i=0; i<map2.length; i++) map2[i] = -1;
	      for (int i=0; i<64; i++) map2[map1[i]] = (byte)i; }

	/**
	* Encodes a string into Base64 format.
	* No blanks or line breaks are inserted.
	* @param s  a String to be encoded.
	* @return   A String with the Base64 encoded data.
	*/
	public static String encodeString (String s) {
	   return new String(encode(s.getBytes())); }

	/**
	* Encodes a byte array into Base64 format.
	* No blanks or line breaks are inserted.
	* @param in  an array containing the data bytes to be encoded.
	* @return    A character array with the Base64 encoded data.
	*/
	public static char[] encode (byte[] in) {
	   return encode(in,in.length); }

	/**
	* Encodes a byte array into Base64 format.
	* No blanks or line breaks are inserted.
	* @param in   an array containing the data bytes to be encoded.
	* @param iLen number of bytes to process in <code>in</code>.
	* @return     A character array with the Base64 encoded data.
	*/
	public static char[] encode (byte[] in, int iLen) {
	   int oDataLen = (iLen*4+2)/3;       // output length without padding
	   int oLen = ((iLen+2)/3)*4;         // output length including padding
	   char[] out = new char[oLen];
	   int ip = 0;
	   int op = 0;
	   while (ip < iLen) {
	      int i0 = in[ip++] & 0xff;
	      int i1 = ip < iLen ? in[ip++] & 0xff : 0;
	      int i2 = ip < iLen ? in[ip++] & 0xff : 0;
	      int o0 = i0 >>> 2;
	      int o1 = ((i0 &   3) << 4) | (i1 >>> 4);
	      int o2 = ((i1 & 0xf) << 2) | (i2 >>> 6);
	      int o3 = i2 & 0x3F;
	      out[op++] = map1[o0];
	      out[op++] = map1[o1];
	      out[op] = op < oDataLen ? map1[o2] : '='; op++;
	      out[op] = op < oDataLen ? map1[o3] : '='; op++; }
	   return out; }

	/**
	* Decodes a string from Base64 format.
	* @param s  a Base64 String to be decoded.
	* @return   A String containing the decoded data.
	* @throws   IllegalArgumentException if the input is not valid Base64 encoded data.
	*/
	public static String decodeString (String s) {
	   return new String(decode(s)); }

	/**
	* Decodes a byte array from Base64 format.
	* @param s  a Base64 String to be decoded.
	* @return   An array containing the decoded data bytes.
	* @throws   IllegalArgumentException if the input is not valid Base64 encoded data.
	*/
	public static byte[] decode (String s) {
	   return decode(s.toCharArray()); }

	/**
	* Decodes a byte array from Base64 format.
	* No blanks or line breaks are allowed within the Base64 encoded data.
	* @param in  a character array containing the Base64 encoded data.
	* @return    An array containing the decoded data bytes.
	* @throws    IllegalArgumentException if the input is not valid Base64 encoded data.
	*/
	public static byte[] decode (char[] in) {
	   int iLen = in.length;
	   if (iLen%4 != 0) throw new IllegalArgumentException ("Length of Base64 encoded input string is not a multiple of 4.");
	   while (iLen > 0 && in[iLen-1] == '=') iLen--;
	   int oLen = (iLen*3) / 4;
	   byte[] out = new byte[oLen];
	   int ip = 0;
	   int op = 0;
	   while (ip < iLen) {
	      int i0 = in[ip++];
	      int i1 = in[ip++];
	      int i2 = ip < iLen ? in[ip++] : 'A';
	      int i3 = ip < iLen ? in[ip++] : 'A';
	      if (i0 > 127 || i1 > 127 || i2 > 127 || i3 > 127)
		 throw new IllegalArgumentException ("Illegal character in Base64 encoded data.");
	      int b0 = map2[i0];
	      int b1 = map2[i1];
	      int b2 = map2[i2];
	      int b3 = map2[i3];
	      if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0)
		 throw new IllegalArgumentException ("Illegal character in Base64 encoded data.");
	      int o0 = ( b0       <<2) | (b1>>>4);
	      int o1 = ((b1 & 0xf)<<4) | (b2>>>2);
	      int o2 = ((b2 &   3)<<6) |  b3;
	      out[op++] = (byte)o0;
	      if (op<oLen) out[op++] = (byte)o1;
	      if (op<oLen) out[op++] = (byte)o2; }
	   return out; }

	// Dummy constructor.
	private Base64Coder() {}

}
