package example.api.client;

@FunctionalInterface
public interface RequestFactory<T> {

    public T getRequest();
}
