//package example.blockcache;
//
//import org.apache.hadoop.hbase.io.hfile.*;
//
//import java.nio.ByteBuffer;
//
//public class Main {
//
//    public static void main(String[] args) {
//
//        lruBlockCache();
//    }
//
//
//
//
//    public static void lruBlockCache(){
//        LruBlockCache cachedBlocks = new LruBlockCache(1024 * 1024 * 10, 1024);
//
//        HFileBlock hFileBlock = new HFileBlock(
//                BlockType.DATA,
//                1024,
//                1024,
//                0,
//                ByteBuffer.allocate(1024),
//                true,
//                0,
//                1024,
//                1024,
//                new HFileContext());
//        cachedBlocks.cacheBlock(new BlockCacheKey("hfile-demo", 10L), hFileBlock);
//
//        Cacheable block = cachedBlocks.getBlock(new BlockCacheKey("hfile-demo", 10L), false, false, false);
//
//        System.out.println(block);
//    }
//
//
//}
