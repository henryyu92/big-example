package example.api;

/**
 * HBase Connection 是线程安全的，并且维护了 Client 到所有 RegionServer 的连接，
 *
 *      +------------+      +----------------+
 *      | Connection |------| ConnectionPool |-------
 *      +------------+      +----------------+
 *
 */
public class ConnectionFactory {
}
