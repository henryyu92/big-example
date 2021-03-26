## Region Split

Region 的分裂是在 RegionServer 上独立运行的，Master 不会参与。当一个 Region 内的存储文件大于 ```hbase.hregion.max.filesize``` 时，该 Region 就需要分裂为两个