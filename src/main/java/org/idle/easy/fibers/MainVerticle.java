package org.idle.easy.fibers;

import co.paralleluniverse.fibers.Suspendable;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.auth.oauth2.AccessToken;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.providers.GoogleAuth;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.sync.Sync;
import io.vertx.ext.sync.SyncVerticle;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.*;
import io.vertx.redis.RedisClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.vertx.ext.sync.Sync.awaitResult;


public class MainVerticle extends SyncVerticle {

    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    private static final String COLLECTION_NAME = "Entities";
    private WebClient webClient;
    private MongoClient mongoClient;
    private RedisClient redisClient;
    private CookieCipher cookieCipher;

    @Override
    @Suspendable
    public void start(Future<Void> startFuture) throws Exception {
        super.start(startFuture);
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        router.route().handler(CorsHandler.create("*")
                .allowedMethod(HttpMethod.GET)
                .allowedMethod(HttpMethod.POST)
                .allowedMethod(HttpMethod.OPTIONS)
                .allowedHeader("Access-Control-Request-Method")
                .allowedHeader("Access-Control-Allow-Credentials")
                .allowedHeader("Access-Control-Allow-Origin")
                .allowedHeader("Access-Control-Allow-Headers")
                .allowedHeader("Content-Type"));
        router.route().handler(CookieHandler.create());
        router.route().handler(BodyHandler.create());

/*      String clientId = "873521963386-08elodg1nfpu2j784bikm66e3hjf4m3p.apps.googleusercontent.com";
        String clientSecret = "sbYikW3olRC0O4SqvSD3xQrA";
        OAuth2Auth oauth2Provider = GoogleAuth.create(vertx, clientId, clientSecret);
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
        router.route().handler(UserSessionHandler.create(oauth2Provider));
        AuthHandler redirectAuthHandler = RedirectAuthHandler.create(oauth2Provider);
        router.route("/*").handler(redirectAuthHandler);
        router.post("/login").handler(FormLoginHandler.create(oauth2Provider));
         */
        router.route().failureHandler(ErrorHandler.create());
        router.routeWithRegex("/roomScheduler/api\\/.*").handler(Sync.fiberHandler(this::authenticate));
        router.get("/roomScheduler/api/getWebContent").handler(Sync.fiberHandler(this::getWebContent));
        router.get("/roomScheduler/api/entities").handler(Sync.fiberHandler(this::getAllEntities));
        router.get("/roomScheduler/api/entities/:id").handler(Sync.fiberHandler(this::getEntityById));
        router.put("/roomScheduler/api/entities").handler(Sync.fiberHandler(this::saveNewEntity));
        router.get("/roomScheduler/api/googleauth").handler(Sync.fiberHandler(this::startGoogleAuth));
        router.get("/roomScheduler/api/googletoken").handler(Sync.fiberHandler(this::getGoogleToken));
        router.route("/roomScheduler/*").handler(StaticHandler.create()
                .setIndexPage("index.html").setCachingEnabled(false));
        // HttpServer will be automatically shared if port matches
        server.requestHandler(router::accept).listen(8088);
        webClient = WebClient.create(vertx, new WebClientOptions().setSsl(true));
//        mongoClient = MongoClient.createShared(vertx, new JsonObject().put("connection_string", "mongodb://127.0.0.1:27017/testDb"));
        redisClient = RedisClient.create(vertx);
        cookieCipher = new CookieCipher();
    }

    @Suspendable
    private void saveNewEntity(RoutingContext routingContext) {
        String userId = routingContext.get("userId");
        final JsonObject entry = routingContext.getBodyAsJson();
        JsonObject scheduledRoom = entry.getJsonObject("scheduledRoom");
        Instant startDate = scheduledRoom.getInstant("startDate");
        Instant endDate = scheduledRoom.getInstant("endDate");
        scheduledRoom.put("startDate", startDate).put("endDate", endDate);
        Long ts = startDate.toEpochMilli();
        long result = awaitResult(h -> redisClient.zadd("user:entry:" + userId, ts, entry.encodePrettily(), h));
        logger.info("saveEntity: {0},\nresult: {1}", entry.encodePrettily(), result);

        routingContext.response().end(entry.encode());
    }

    @Suspendable
    private void getAllEntities(RoutingContext routingContext) {
        String userId = routingContext.get("userId");
        JsonArray entries = awaitResult(h -> redisClient.zrange("user:entry:" + userId, 0, -1, h));
        logger.info("getAllEntities: key:{0} \n{1}", "user:entry:" + userId, entries.encode());
        routingContext.response().end(entries.encodePrettily());
    }

    @Suspendable
    private void getEntityById(RoutingContext routingContext) {
        final JsonObject query = new JsonObject()
                .put("_id", routingContext.pathParam("id"));
        final List<JsonObject> entity = awaitResult(h -> mongoClient.find(COLLECTION_NAME, query, h));
        routingContext.response()
                .end(Json.encodePrettily(entity));
    }

    @Suspendable
    private void getWebContent(RoutingContext routingContext) {
        routingContext.response().putHeader("Location", "/roomScheduler/")
                .setStatusCode(302)
                .end();
    }

    @Suspendable
    private void authenticate(RoutingContext routingContext) {
        Cookie cookie = routingContext.getCookie("roomScheduler");
        String requestPath = routingContext.request().path();
        if (cookie == null) {
            if (!"/roomScheduler/api/googleauth".equals(requestPath) && !"/roomScheduler/api/googletoken".equals(requestPath)) {
//            routingContext.response().putHeader("Location", "/index.html")
//                    .setStatusCode(301)
//                    .end();
                routingContext.reroute(HttpMethod.GET, "/roomScheduler/");
                return;
            }
        } else {
            String decryptedCookie = cookieCipher.decryptCookie(cookie.getValue());
            routingContext.put("userId", decryptedCookie.split(":")[0]);
            logger.info("decryptedCookie: {0} ", decryptedCookie);
//            routingContext.next();
        }
        routingContext.next();
    }

    @Suspendable
    private void startGoogleAuth(RoutingContext routingContext) {
        String clientId = "873521963386-08elodg1nfpu2j784bikm66e3hjf4m3p.apps.googleusercontent.com";
        String clientSecret = "sbYikW3olRC0O4SqvSD3xQrA";

        OAuth2Auth oauth2 = GoogleAuth.create(vertx, clientId, clientSecret);

        final String callbackUrl = "http://localhost:8088/roomScheduler/api/googletoken";
        // Authorization oauth2 URI
        String authorization_uri = oauth2.authorizeURL(new JsonObject()
                .put("redirect_uri", callbackUrl)
                .put("scope", "openid profile email https://www.googleapis.com/auth/calendar")
                .put("approval_prompt", "force")
                .put("access_type", "offline")
                .put("state", UUID.randomUUID().toString()));

        routingContext.response()
                .putHeader("Location", authorization_uri)
                .setStatusCode(302)
                .end();
    }

    @Suspendable
    private void getGoogleToken(RoutingContext routingContext) {
        String clientId = "873521963386-08elodg1nfpu2j784bikm66e3hjf4m3p.apps.googleusercontent.com";
        String clientSecret = "sbYikW3olRC0O4SqvSD3xQrA";
        OAuth2Auth oauth2Provider = GoogleAuth.create(vertx, clientId, clientSecret);
        final String callbackUrl = "http://localhost:8088/roomScheduler/api/googletoken";

        final JsonObject tokenConfig = new JsonObject()
                .put("code", routingContext.request().getParam("code"))
                .put("redirect_uri", callbackUrl);

        final AccessToken token = awaitResult(h -> oauth2Provider.getToken(tokenConfig, h));

        String uri = "/oauth2/v1/userinfo?v=2&oauth_token=" + token.principal().getValue("access_token");
        HttpResponse<Buffer> response = awaitResult(h -> webClient.get(443, "www.googleapis.com",uri).ssl(true).send(h));
        JsonObject userInfo = response.bodyAsJsonObject();
        logger.info("AccessToken: {0}", Json.encodePrettily(token.principal()));
        logger.info("userInfo: {0}", Json.encodePrettily(userInfo));
        JsonObject principal = token.principal();
        final String userId = userInfo.getString("id");
        Long p = awaitResult(h -> redisClient.hset("user:" + userId, "principal", principal.encodePrettily(), h));
        Long u = awaitResult(h -> redisClient.hset("user:" + userId, "userInfo", userInfo.encodePrettily(), h));

        Cookie cookie = createCookie(userId, principal.getLong("expires_at").toString(), principal.getString("access_token"));
        routingContext.addCookie(cookie);
        routingContext.response().putHeader("Location", "/roomScheduler/")
                .setStatusCode(301)
                .end();
    }

    @Suspendable
    private Cookie createCookie(String userId, String expiresAt, String accessToken) {
        final String cookieSource = String.join(":", userId, expiresAt, accessToken);
        String encryptedCookie = cookieCipher.encryptCookie(cookieSource);
        Cookie cookie = Cookie.cookie("roomScheduler", encryptedCookie);
        cookie.setMaxAge(31536000000L / 1000);
        cookie.setPath("/roomScheduler/");
        cookie.setHttpOnly(false);
        return cookie;
    }
}
