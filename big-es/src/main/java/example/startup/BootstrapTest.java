package example.startup;

import java.util.concurrent.CountDownLatch;

public class BootstrapTest {

    public static void main(String[] args) {
        BootstrapTest bootStrapTest = new BootstrapTest();
        bootStrapTest.start();
        System.out.println("shutting down");
    }

    private static final CountDownLatch latch = new CountDownLatch(1);
    private final Thread keepAlive;
    public BootstrapTest(){
        keepAlive = new Thread(()->{
            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        keepAlive.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
    }

    public void start(){
        keepAlive.start();
    }

    public void addShutdownHook(){
        Thread shutdownHook = new Thread(this::doShutdown);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public void doShutdown(){
        System.out.println("shutting down ....");
    }
}
