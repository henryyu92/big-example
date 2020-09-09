package example.api.client;

@FunctionalInterface
public interface RequestFactory<T> {

    public T getRequest();

    // IndexRequest
    // DeleteRequest
    // BulkRequest
    // GetRequest
    // SearchRequest
    // SearchScrollRequest
}
