### 通用参数

- pretty - 当在请求中添加参数 pretty=true 时，请求的返回值是经过格式化后的 JSON 数据，阅读起来更方便
- human - 当在请求中添加参数 human=true 时，返回结果的统计数据更适合人类阅读，默认是 false
- filter_path - 请求中添加参数 filter_path=content 时，可以过来返回值的内容，多个值支持 , 隔开和通配符；如 ``` curl -X GET HTTP '127.0.0.1:9200/_search?pretty=true&filter_path=took,hits._id,hits._score'```