package com.gdut.rpcstudy.demo.framework.connect;

import com.gdut.rpcstudy.demo.framework.URL;
import com.gdut.rpcstudy.demo.framework.protocol.netty.asyn.NettyAsynHandler;
import com.gdut.rpcstudy.demo.framework.serialize.handler.RpcDecoder;
import com.gdut.rpcstudy.demo.framework.serialize.handler.RpcEncoder;
import com.gdut.rpcstudy.demo.framework.serialize.tranobject.RpcRequest;
import com.gdut.rpcstudy.demo.framework.serialize.tranobject.RpcResponse;
import com.gdut.rpcstudy.demo.register.zk.ZkRegister;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author: lele
 * @date: 2019/11/21 上午11:58
 * 管理连接池
 */

public class ConnectManager {


    private Boolean isShutDown = false;

    /**
     * 客户端链接服务端超时时间
     */
    private long connectTimeoutMillis = 6000;

    /**
     * 自定义6个线程组用于客户端服务
     */
    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(6);
    /**
     * 标示非init状态下，addServerAfter才能起作用
     */
    private CountDownLatch serverInitCountDownLatch;

    /**
     * 存放服务对应的访问数，用于轮询
     */
    private Map<String, AtomicInteger> pollingMap = new ConcurrentHashMap<>();

    /**
     * 对于每个服务都有一个锁，每个锁都有个条件队列，用于控制链接获取
     */
    private Map<String, Object[]> serviceCondition = new ConcurrentHashMap<>();


    /**
     * 存放服务端地址和handler的关系
     */
    private Map<String, Map<URL, NettyAsynHandler>> serverClientMap = new ConcurrentHashMap<>();


    /**
     * 存放不活跃的服务端地址和handler的关系，当活跃时添加回正式的handler
     */
    private Map<String, Map<URL, NettyAsynHandler>> inactiveClientMap = new ConcurrentHashMap<>();

    /**
     * 用来初始化客户端
     */
    private ThreadPoolExecutor clientBooter = new ThreadPoolExecutor(
            16, 16, 600, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1024)
            , new BooterThreadFactory(), new ThreadPoolExecutor.AbortPolicy());

    private static class Holder {
        private static final ConnectManager j = new ConnectManager();
    }

    private ConnectManager() {
        //初始化时把所有的url加进去,这里可能没有可用链接，所以需要添加对节点的监听
        Map<String, List<URL>> allURL = ZkRegister.getInstance().getAllURL();
        for (String s : allURL.keySet()) {
            //为每个服务添加锁和条件队列，通过条件队列控制客户端链接获取
            addLockToService(s);
        }
        addServerInit(allURL);
    }

    private void addLockToService(String serviceName) {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        serviceCondition.put(serviceName, new Object[]{lock, condition});
    }

    public static ConnectManager getInstance() {

        return Holder.j;
    }


    /**
     * 添加该服务对应的链接和handler
     *
     * @param serviceName
     * @param url
     * @param handler
     */
    public void addConnection(String serviceName, URL url, NettyAsynHandler handler) {
        Map<URL, NettyAsynHandler> handlerMap;
        if (!serverClientMap.containsKey(serviceName)) {
            handlerMap = new HashMap<>();
        } else {
            handlerMap = serverClientMap.get(serviceName);
        }
        handlerMap.put(url, handler);
        //添加服务名和对应的url:客户端链接
        serverClientMap.put(serviceName, handlerMap);
        //如果处于初始化状态，则countdown防止新增节点事件再次新增客户端
        if(serverInitCountDownLatch.getCount()!=0){
            serverInitCountDownLatch.countDown();
        }
        //唤醒等待客户端链接的线程
        signalAvailableHandler(serviceName);
    }

    /**
     * 获取对应服务下的handler，通过轮询获取
     *
     * @param servicName
     * @return
     */
    public NettyAsynHandler getConnectionWithPolling(String servicName) {
        Map<URL, NettyAsynHandler> urlNettyAsynHandlerMap = serverClientMap.get(servicName);
        int size = 0;
        //先尝试获取
        if (urlNettyAsynHandlerMap != null) {
            size = urlNettyAsynHandlerMap.size();
        }
        //不行就自选等待
        while (!isShutDown && size <= 0) {
            try {
                //自旋等待可用服务出现，因为客户端与服务链接需要一定的时间，如果直接返回会出现空指针异常
                boolean available = waitingForHandler(servicName);
                if (available) {
                    urlNettyAsynHandlerMap = serverClientMap.get(servicName);
                    size = urlNettyAsynHandlerMap.size();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("出错", e);
            }
        }
        //获取对应的访问次数
        AtomicInteger count = pollingMap.get(servicName);
        int index = (count.addAndGet(1) + size) % size;
        if(index==0){
            index++;
        }
        Collection<NettyAsynHandler> values = urlNettyAsynHandlerMap.values();
        //取出相应的handler
        NettyAsynHandler nettyAsynHandler = null;
        int i=0;
        Iterator<NettyAsynHandler> iterator = values.iterator();
        while(iterator.hasNext()&&i<index){
            nettyAsynHandler = iterator.next();
        }

        return nettyAsynHandler;
    }

    /**
     * 等待一定时间，等handler和相应的server建立建立链接，用条件队列控制
     *
     * @param serviceName
     * @return
     * @throws InterruptedException
     */
    private boolean waitingForHandler(String serviceName) throws InterruptedException {
        Object[] objects = serviceCondition.get(serviceName);
        ReentrantLock lock = (ReentrantLock) objects[0];
        lock.lock();
        Condition condition = (Condition) objects[1];
        try {
            return condition.await(this.connectTimeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 去掉所有与该url链接的客户端,并且关闭客户端链接
     *
     * @param url
     */
    public void removeURL(URL url, String serviceName) {
        NettyAsynHandler nettyAsynHandler = serverClientMap.get(serviceName).get(url);
        if (nettyAsynHandler != null) {
            nettyAsynHandler.close();
            serverClientMap.get(serviceName).remove(url);
        }
        Map<URL, NettyAsynHandler> map = null;
        if ((map = inactiveClientMap.get(serviceName)) != null) {
            NettyAsynHandler nettyAsynHandler1 = map.get(url);
            if (nettyAsynHandler1 != null) {
                nettyAsynHandler1.close();
                inactiveClientMap.get(serviceName).remove(url);
            }
        }

    }


    /**
     * 去掉可用的服务，把他加入到不活跃的列表
     *
     * @param url
     */
    public void addInactiveURL(URL url, String serviceName) {


        NettyAsynHandler nettyAsynHandler = serverClientMap.get(serviceName).get(url);
        if (inactiveClientMap.get(serviceName) == null) {
            inactiveClientMap.put(serviceName, new HashMap<>());
        }
        inactiveClientMap.get(serviceName).put(url, nettyAsynHandler);
        serverClientMap.get(serviceName).remove(url);

    }


    /**
     * 释放对应服务的条件队列,代表有客户端链接可用了
     *
     * @param serviceName
     */
    private void signalAvailableHandler(String serviceName) {
        Object[] objects = serviceCondition.get(serviceName);
        ReentrantLock lock = (ReentrantLock) objects[0];
        lock.lock();
        Condition condition = (Condition) objects[1];
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加server，并启动对应的服务器
     *
     * @param allURL
     */
    public void addServerInit(Map<String, List<URL>> allURL) {
        Collection<List<URL>> values = allURL.values();

        Iterator<List<URL>> iterator = values.iterator();
        int res=0;
        while(iterator.hasNext()){
            List<URL> next = iterator.next();
            res+=next.size();
        }
        serverInitCountDownLatch =new CountDownLatch(res);
        for (String s : allURL.keySet()) {
            pollingMap.put(s, new AtomicInteger(0));
            List<URL> urls = allURL.get(s);
            for (URL url : urls) {
                //提交创建任务
                clientBooter.submit(new Runnable() {
                    @Override
                    public void run() {
                        createClient(s, eventLoopGroup, url);
                    }
                });
            }
        }
    }

    /**
     * 当新节点出现后添加
     *
     * @param url
     * @param serviceName
     */
    public void addServerAfter(URL url, String serviceName) {
        if(serverInitCountDownLatch.getCount()==0){
            Map<URL, NettyAsynHandler> map = null;
            if ((map = serverClientMap.get(serviceName)) == null) {
                map = new HashMap<>();
                serverClientMap.put(serviceName, map);
                addLockToService(serviceName);
            } else {
                NettyAsynHandler nettyAsynHandler = map.get(url);
                if (nettyAsynHandler != null) {
                    return;
                }
            }
            clientBooter.submit(new Runnable() {
                @Override
                public void run() {
                    createClient(serviceName, eventLoopGroup, url);
                }
            });
        }

    }


    /**
     * 创建客户端,持久化链接
     *
     * @param serviceName
     * @param eventLoopGroup
     * @param url
     */
    public void createClient(String serviceName, EventLoopGroup eventLoopGroup, URL url) {
        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler((new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                //把request实体变为字节
                                .addLast(new RpcEncoder(RpcRequest.class))
                                //把返回的response字节变为对象
                                .addLast(new RpcDecoder(RpcResponse.class))
                                .addLast(new NettyAsynHandler(url));
                    }
                }));

        ChannelFuture channelFuture = b.connect(url.getHostname(), url.getPort());

        channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                //链接成功后的操作，把相应的url地址和客户端链接存入
                if (channelFuture.isSuccess()) {
                    System.out.println("创建客户端完毕"+channelFuture.channel().id().asShortText());
                    NettyAsynHandler handler = channelFuture.channel().pipeline().get(NettyAsynHandler.class);
                    addConnection(serviceName, url, handler);

                }
            }
        });
    }


    /**
     * 关闭方法，关闭每个客户端链接，释放所有锁，关掉创建链接的线程池，和客户端的处理器
     */
    public void stop() {
        isShutDown = true;
        for (Map<URL, NettyAsynHandler> urlNettyAsynHandlerMap : serverClientMap.values()) {
            urlNettyAsynHandlerMap.values().forEach(e -> e.close());
        }
        for (String s : serviceCondition.keySet()) {
            signalAvailableHandler(s);
        }
        clientBooter.shutdown();
        eventLoopGroup.shutdownGracefully();
    }


    /**
     * 启动客户端链接的自定义线程工厂
     */
    static class BooterThreadFactory implements ThreadFactory {

        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        BooterThreadFactory() {
            group = new ThreadGroup("connectManger");
            group.setDaemon(false);
            group.setMaxPriority(5);
            namePrefix = "clientBooter-" +
                    poolNumber.getAndIncrement() +
                    "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r,
                    namePrefix + threadNumber.getAndIncrement(),
                    0);
            return t;
        }
    }

}
