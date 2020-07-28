package example;

import org.apache.hadoop.hbase.util.HasThread;


public class Main extends HasThread {

    private volatile boolean loop;

    public Main(){
        super("Main in test");
        loop = true;
    }

    @Override
    public void run() {
        System.out.println("thread in main");
        while (loop){
            System.out.println("looping...");
        }
    }

    public static void main(String[] args) throws InterruptedException {

        Main main = new Main();

        main.start();

        main.join();

        System.out.println("main");


    }
}
