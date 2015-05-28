/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.handler;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.shiro.ShiroAuth;
import io.vertx.ext.auth.shiro.ShiroAuthRealmType;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class RedirectAuthHandlerTest extends AuthHandlerTestBase {

  protected AtomicReference<String> sessionCookie = new AtomicReference<>();

  @Test
  public void testLogin() throws Exception {

    doLogin(rc -> {
      Session sess = rc.session();
      assertNotNull(sess);
      assertEquals(sessionCookie.get().substring(18, 54), sess.id());
      assertNotNull(rc.user());
      rc.response().end("Welcome to the protected resource!");
    });
    // And request it again
    testRequest(HttpMethod.GET, "/protected/somepage", req -> {
      req.putHeader("cookie", sessionCookie.get());
    }, resp -> {
    }, 200, "OK", "Welcome to the protected resource!");
    // Now logout
    router.route("/logout").handler(rc -> {
      System.out.println("Logging out");
      rc.setUser(null);
      rc.response().end("logged out");
    });
    testRequest(HttpMethod.GET, "/logout", req -> {
      req.putHeader("cookie", sessionCookie.get());
    }, resp -> {
    }, 200, "OK", "logged out");
    testRequest(HttpMethod.GET, "/protected/somepage", req -> {
      req.putHeader("cookie", sessionCookie.get());
    }, resp -> {
      String location = resp.headers().get("location");
      assertNotNull(location);
      assertEquals("/loginpage", location);
    }, 302, "Found", null);
  }

  @Test
  public void testLoginFailBadUser() throws Exception {
    testLoginFail(true);
  }

  @Test
  public void testLoginFailBadPassword() throws Exception {
    testLoginFail(false);
  }

  @Override
  protected AuthHandler createAuthHandler(AuthProvider authProvider) {
    return RedirectAuthHandler.create(authProvider);
  }

  @Override
  protected boolean requiresSession() {
    return true;
  }

  private void testLoginFail(boolean badUser) throws Exception {

    doLoginFail(badUser, rc -> {
      Session sess = rc.session();
      assertNotNull(sess);
      assertEquals(sessionCookie.get().substring(18, 54), sess.id());
      assertNotNull(rc.user());
      rc.response().end("Welcome to the protected resource!");
    });
  }

  private void doLogin(Handler<RoutingContext> handler) throws Exception {
    doLoginCommon(handler);
    testRequest(HttpMethod.POST, "/login", req -> {
      String boundary = "dLV9Wyq26L_-JQxk6ferf-RT153LhOO";
      Buffer buffer = Buffer.buffer();
      String str =
        "--" + boundary + "\r\n" +
          "Content-Disposition: form-data; name=\"username\"\r\n\r\ntim\r\n" +
          "--" + boundary + "\r\n" +
          "Content-Disposition: form-data; name=\"password\"\r\n\r\nsausages\r\n" +
          "--" + boundary + "--\r\n";
      buffer.appendString(str);
      req.putHeader("content-length", String.valueOf(buffer.length()));
      req.putHeader("content-type", "multipart/form-data; boundary=" + boundary);
      req.putHeader("cookie", sessionCookie.get());
      req.write(buffer);
    }, resp -> {
      String location = resp.headers().get("location");
      assertNotNull(location);
      assertEquals("/protected/somepage", location);
    }, 302, "Found", null);
    testRequest(HttpMethod.GET, "/protected/somepage", req -> {
      req.putHeader("cookie", sessionCookie.get());
    }, resp -> {
    }, 200, "OK", "Welcome to the protected resource!");
  }

  private void doLoginCommon(Handler<RoutingContext> handler) throws Exception {
    doLoginCommon(handler, null);
  }

  private void doLoginCommon(Handler<RoutingContext> handler, Set<String> authorities) throws Exception {
    router.route().handler(BodyHandler.create());
    router.route().handler(CookieHandler.create());
    SessionStore store = LocalSessionStore.create(vertx);
    router.route().handler(SessionHandler.create(store));
    JsonObject authConfig = new JsonObject().put("properties_path", "classpath:login/loginusers.properties");
    AuthProvider authProvider = ShiroAuth.create(vertx, ShiroAuthRealmType.PROPERTIES, authConfig);
    router.route().handler(UserSessionHandler.create(authProvider));
    AuthHandler authHandler = RedirectAuthHandler.create(authProvider);
    if (authorities != null) {
      authHandler.addAuthorities(authorities);
    }
    router.route("/protected/*").handler(authHandler);
    router.route("/protected/somepage").handler(handler);
    router.route("/loginpage").handler(rc -> {
      rc.response().putHeader("content-type", "text/html").end(loginHTML);
    });
    router.route("/login").handler(FormLoginHandler.create(authProvider));
    testRequest(HttpMethod.GET, "/protected/somepage", null, resp -> {
      String location = resp.headers().get("location");
      assertNotNull(location);
      assertEquals("/loginpage", location);
      String setCookie = resp.headers().get("set-cookie");
      assertNotNull(setCookie);
      sessionCookie.set(setCookie);
    }, 302, "Found", null);
    testRequest(HttpMethod.GET, "/loginpage", req -> {
      req.putHeader("cookie", sessionCookie.get());
    }, resp -> {
    }, 200, "OK", loginHTML);
  }

  private void doLoginFail(boolean badUser, Handler<RoutingContext> handler) throws Exception {
    doLoginCommon(handler);
    testRequest(HttpMethod.POST, "/login", req -> {
      String boundary = "dLV9Wyq26L_-JQxk6ferf-RT153LhOO";
      Buffer buffer = Buffer.buffer();
      String username = badUser ? "jim" : "tim";
      String password = badUser ? "sausages" : "fishfingers";
      String str =
        "--" + boundary + "\r\n" +
          "Content-Disposition: form-data; name=\"username\"\r\n\r\n" + username + "\r\n" +
          "--" + boundary + "\r\n" +
          "Content-Disposition: form-data; name=\"password\"\r\n\r\n" + password + "\r\n" +
          "--" + boundary + "--\r\n";
      buffer.appendString(str);
      req.putHeader("content-length", String.valueOf(buffer.length()));
      req.putHeader("content-type", "multipart/form-data; boundary=" + boundary);
      req.putHeader("cookie", sessionCookie.get());
      req.write(buffer);
    }, resp -> {
    }, 403, "Forbidden", null);
    testRequest(HttpMethod.GET, "/protected/somepage", req -> {
      req.putHeader("cookie", sessionCookie.get());
    }, resp -> {
      String location = resp.headers().get("location");
      assertNotNull(location);
      assertEquals("/loginpage", location);
    }, 302, "Found", null);
  }

  String loginHTML = "<html>\n" +
    "<body>\n" +
    "<h2>Please login {{foo}}</h2><br>\n" +
    "<form action=\"/login\" method=\"post\">\n" +
    "  <div>\n" +
    "    <label>Username:</label>\n" +
    "    <input type=\"text\" name=\"username\"/>\n" +
    "  </div>\n" +
    "  <div>\n" +
    "    <label>Password:</label>\n" +
    "    <input type=\"password\" name=\"password\"/>\n" +
    "  </div>\n" +
    "  <div>\n" +
    "    <input type=\"submit\" value=\"Log In\"/>\n" +
    "  </div>\n" +
    "</form>\n" +
    "</body>\n" +
    "</html>";
}
