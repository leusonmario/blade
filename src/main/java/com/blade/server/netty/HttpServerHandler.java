package com.blade.server.netty;

import com.blade.Blade;
import com.blade.BladeException;
import com.blade.ioc.Ioc;
import com.blade.kit.PathKit;
import com.blade.kit.StringKit;
import com.blade.mvc.RouteHandler;
import com.blade.mvc.WebContext;
import com.blade.mvc.handler.RouteViewResolve;
import com.blade.mvc.http.HttpRequest;
import com.blade.mvc.http.HttpResponse;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;
import com.blade.mvc.route.Route;
import com.blade.mvc.route.RouteMatcher;
import com.blade.mvc.ui.DefaultUI;
import com.blade.mvc.ui.template.TemplateEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpUtil.is100ContinueExpected;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author biezhi
 *         2017/5/31
 */
@ChannelHandler.Sharable
public class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    public static final Logger log = LoggerFactory.getLogger(HttpServerHandler.class);

    private Ioc ioc;
    private RouteMatcher routeMatcher;
    private RouteViewResolve routeViewResolve;
    private Set<String> statics;
    private boolean showFileList;
    private TemplateEngine templateEngine;
    private StaticFileHandler staticFileHandler;

    private static final ExecutorService threadPool = Executors.newFixedThreadPool(8);

    public HttpServerHandler(Blade blade) {
        this.ioc = blade.ioc();
        this.statics = blade.getStatics();
        this.templateEngine = blade.templateEngine();
        this.routeMatcher = blade.routeMatcher();
        this.routeViewResolve = new RouteViewResolve(blade);
        this.staticFileHandler = new StaticFileHandler(blade);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest fullHttpRequest) throws Exception {

        if (is100ContinueExpected(fullHttpRequest)) {
            ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
        }

        Request request = new HttpRequest(fullHttpRequest);
        Response response = new HttpResponse(ctx, templateEngine);
        response.header("Server", "blade/" + Blade.VER);

        // reuqest uri
        String uri = PathKit.getRelativePath(request.uri(), request.contextPath());
        log.debug("{}\t{}\t{}", request.protocol(), request.method(), uri);

        // 判断是否是静态资源
        try {
            if (isStaticFile(uri)) {
                staticFileHandler.execute(ctx, request, response, uri);
            } else {
                WebContext.set(new WebContext(request, response));
                // web hook
                int interrupts = routeMatcher.getBefore(uri).stream()
                        .mapToInt(route -> this.invokeHook(request, response, route)).sum();

                if (interrupts == 0) {
                    Route route = routeMatcher.lookupRoute(request.method(), uri);
                    if (null != route) {
                        // execute
                        this.routeHandle(request, response, route);
                        routeMatcher.getAfter(uri).forEach(r -> this.invokeHook(request, response, r));
                    } else {
                        // 404
                        sendError(ctx, NOT_FOUND, String.format(DefaultUI.VIEW_404, uri));
                    }
                }
                WebContext.remove();
            }
        } catch (BladeException e) {
            log.error("", e);

            String error = e.getMessage();

            String contentType = response.contentType();
            if (contentType.contains("html")) {
                StringWriter sw = new StringWriter();
                PrintWriter writer = new PrintWriter(sw);
                writer.write(String.format(DefaultUI.HTML, e.getClass() + " : " + e.getMessage()));
                writer.write("\r\n");
                e.printStackTrace(writer);
                writer.println(DefaultUI.END);
                error = sw.toString();
            }
            sendError(ctx, INTERNAL_SERVER_ERROR, error);
        } catch (Exception e) {
            log.error("", e);
            sendError(ctx, INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("", cause);
        if (ctx.channel().isActive()) {
            sendError(ctx, INTERNAL_SERVER_ERROR);
        }
    }

    private boolean isStaticFile(String uri) {
        Optional<String> result = statics.stream().filter(s -> s.equals(uri) || uri.startsWith(s)).findFirst();
        return result.isPresent();
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        sendError(ctx, status, "");
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
        boolean isHtml = StringKit.isNotBlank(content);
        content = isHtml ? content : "Failure: " + status + "\r\n";
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, status, Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
        response.headers().set(CONTENT_TYPE, isHtml ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Actual routing method execution
     *
     * @param request  request object
     * @param response response object
     * @param route    route object
     */
    private void routeHandle(Request request, Response response, Route route) {
        Object target = route.getTarget();
        if (null == target) {
            Class<?> clazz = route.getAction().getDeclaringClass();
            target = ioc.getBean(clazz);
            route.setTarget(target);
        }
        if (route.getTargetType() == RouteHandler.class) {
            RouteHandler routeHandler = (RouteHandler) target;
            routeHandler.handle(request, response);
        } else {
            routeViewResolve.handle(request, response, route);
        }
    }

    /**
     * Methods to perform the hooks
     *
     * @param request  request object
     * @param response response object
     * @return Return execute is ok
     */
    private int invokeHook(Request request, Response response, Route route) {
        return routeViewResolve.invokeHook(request, response, route) ? 0 : 1;
    }

}