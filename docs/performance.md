# Đo hiệu năng thống kê và sao kê

Benchmark thủ công dùng `ReportPerformanceIT` với PostgreSQL 17 tạm do Testcontainers tạo. Dữ liệu cố định gồm 20 batch × 4.000 khoản chi (80.000 dòng) và 8.000 giao dịch của một ví. Thời gian là một lần gọi service trên Docker Desktop/JDK 25, tính cả truy vấn và tạo tệp; số đo sẽ thay đổi theo máy và trạng thái cache.

| Tác vụ | Trước | Sau |
| --- | ---: | ---: |
| Thống kê theo danh mục | 507 ms | 57 ms |
| Thống kê theo tháng | 470 ms | 58 ms |
| Sao kê CSV toàn bộ 8.000 giao dịch | 243 ms | 220 ms |
| Sao kê PDF toàn bộ 8.000 giao dịch | 5.418 ms | 5.628 ms |
| CSV phần đầu 500 giao dịch | — | 22 ms |
| PDF phần đầu 500 giao dịch | — | 339 ms |

Thống kê được cải thiện vì PostgreSQL tính `SUM/COUNT` và chỉ gửi kết quả theo nhóm; `ByCategoryStrategy` và `ByMonthStrategy` vẫn chọn biểu thức nhóm. Phép nhóm trong bộ nhớ cũ được dùng làm tham chiếu kiểm thử trên đủ 80.000 dòng. Bản xuất toàn bộ vẫn giữ giới hạn 10.000 giao dịch và cùng kết quả; PDF nhiều trang vẫn tốn thời gian dựng tệp. Chế độ `page`/`size` giới hạn tối đa 1.000 giao dịch mỗi tệp; giao diện web chọn 500 để giảm thời gian chờ một lượt tải. `CsvStatementCreator` và `PdfStatementCreator` vẫn tạo exporter qua Factory Method.

Checksum SHA-256 của hai kết quả thống kê, CSV và văn bản trích từ PDF **trùng nhau trước và sau tối ưu**. Kiểm thử tích hợp còn ghép các phần CSV theo thứ tự và so với bản xuất toàn bộ. Các tệp benchmark riêng nằm trong `target/` và không được commit.

Chạy lại khi Docker hoạt động:

```powershell
mvn '-Dtest=ReportPerformanceIT' '-Dbenchmark.label=local' test
```

Kết quả nằm tại `target/report-benchmark-local.properties`. Benchmark tạo database tạm và không dùng volume PostgreSQL của ứng dụng.
