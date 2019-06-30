package me.josephzhu.proxytest.layer7;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import me.josephzhu.proxytest.BackendThreadModel;
import org.springframework.http.HttpHeaders;

public class FrontendHandler extends ChannelInboundHandlerAdapter {

    private final String remoteHost;
    private final int remotePort;
    private final BackendThreadModel backendThreadModel;
    private Channel outboundChannel;

    public FrontendHandler(String remoteHost, int remotePort, BackendThreadModel backendThreadModel) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.backendThreadModel = backendThreadModel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final Channel inboundChannel = ctx.channel();

        Bootstrap b = new Bootstrap();
        switch (backendThreadModel) {
            case ReuseServerGroup:
            {
                b.group(Layer7ProxyServer.serverWorkerGroup);
                break;
            }
            case IndividualGroup:
            {
                b.group(Layer7ProxyServer.backendWorkerGroup);
                break;
            }
            case ReuseServerThread:
            {
                b.group(inboundChannel.eventLoop());
                break;
            }
            default:
                break;
        }

        b.channel(NioSocketChannel.class).handler(new ChannelInitializer<Channel>() {
             @Override
             protected void initChannel(Channel ch) {
                 ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(65536));
                 ch.pipeline().addLast(new BackendHandler(inboundChannel));
             }
         });
        ChannelFuture f = b.connect(remoteHost, remotePort);
        outboundChannel = f.channel();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest httpRequest = (FullHttpRequest) msg;
            String hostPort = remoteHost +":"+ remotePort;
            httpRequest.headers().set(HttpHeaders.HOST, hostPort);
            httpRequest.headers().add("aa", "bb");
            outboundChannel.writeAndFlush(msg);
        } else {
            closeOnFlush(ctx.channel());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        closeOnFlush(ctx.channel());
    }

    static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
