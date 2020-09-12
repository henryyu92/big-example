package example.api.document;

import example.api.client.ClientFactory;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;

public class GetApi {

    private final RestHighLevelClient client;

    public GetApi(){
        client = ClientFactory.restHighLevelClient();
    }

    // 获取指定 id 文档
    private GetRequest getRequest(String index, String docId){
        GetRequest request = new GetRequest(index);
        if (Strings.hasLength(docId)){
            request = new GetRequest(index, docId);
        }
        return request.fetchSourceContext(FetchSourceContext.DO_NOT_FETCH_SOURCE)
                .storedFields("message")
                .routing("routing")
                .preference("preference")
                .realtime(false)
                .refresh(false)
                .version(4)
                .versionType(VersionType.EXTERNAL);
    }

    // 获取所有文档
    private GetRequest getRequest(String index){
        return getRequest(index, null);
    }

    public void getDoc(String index, String docId) throws IOException {
        final GetRequest request = getRequest(index, docId);

        // 同步
        final GetResponse response = client.get(request, RequestOptions.DEFAULT);
        if (response.isExists()){
            final long version = response.getVersion();
            final String source = response.getSourceAsString();
            System.out.println(source);
        }

        // 异步
        client.getAsync(request, RequestOptions.DEFAULT, new ActionListener<GetResponse>() {
            @Override
            public void onResponse(GetResponse documentFields) {

            }

            @Override
            public void onFailure(Exception e) {

            }
        });

    }

    public static void main(String[] args) throws IOException {

        new GetApi().getDoc("posts", "eI50uE3gR6qkz_Qck8XTBw");
    }
}
