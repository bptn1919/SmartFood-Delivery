import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:fl_chart/fl_chart.dart';
import 'package:intl/intl.dart';
import 'package:testing/core/network/api_constants.dart';

class ChefAnalyticsPage extends StatefulWidget {
  final String token;
  const ChefAnalyticsPage({super.key, required this.token});

  @override
  State<ChefAnalyticsPage> createState() => _ChefAnalyticsPageState();
}

class _ChefAnalyticsPageState extends State<ChefAnalyticsPage> {
  // --- STYLING ---
  final Color _primaryRed = const Color(0xFFE55866);
  final Color _textBrown = const Color(0xFF4A3225);
  final Color _bgCard = const Color(0xFFF9F9F9);

  // --- FILTER STATE ---
  DateTime _startDate = DateTime.now().subtract(const Duration(days: 7));
  DateTime _endDate = DateTime.now();

  // --- DATA STATE ---
  bool _isLoading = true;
  Map<String, dynamic> _trendData = {};
  List<dynamic> _topDishes = [];
  Map<String, dynamic> _heatmapData = {};

  @override
  void initState() {
    super.initState();
    _fetchAllAnalytics();
  }

  // 👇 GỌI ĐỒNG THỜI 3 API ĐỂ TỐI ƯU TỐC ĐỘ HIỂN THỊ
  Future<void> _fetchAllAnalytics() async {
    setState(() => _isLoading = true);
    
    final startStr = DateFormat('yyyy-MM-dd').format(_startDate);
    final endStr = DateFormat('yyyy-MM-dd').format(_endDate.add(const Duration(days: 1)));

    try {
      final headers = {
        'Authorization': 'Bearer ${widget.token}', // Hoặc Bearer tùy Backend
        'Content-Type': 'application/json',
      };

      final urlBase = ApiConstants.baseUrl;

      // Chạy 3 API cùng lúc bằng Future.wait
      final results = await Future.wait([
        http.get(Uri.parse('$urlBase/api/reviews/chef/analytics/issue-trend?range_start=$startStr&range_end=$endStr'), headers: headers),
        http.get(Uri.parse('$urlBase/api/reviews/chef/analytics/top-complained-dishes?range_start=$startStr&range_end=$endStr&limit=5'), headers: headers),
        http.get(Uri.parse('$urlBase/api/reviews/chef/analytics/issue-heatmap?range_start=$startStr&range_end=$endStr'), headers: headers),
      ]);

      debugPrint("🚨 RAW TREND JSON: ${results[0].body}");

      if (mounted) {
        setState(() {
          // Bóc lớp vỏ ['data'] ra trước khi lấy dữ liệu bên trong
          
          if (results[0].statusCode == 200) {
            final decoded0 = jsonDecode(utf8.decode(results[0].bodyBytes));
            _trendData = decoded0['data']['issues'] ?? {};
          }
          
          if (results[1].statusCode == 200) {
            final decoded1 = jsonDecode(utf8.decode(results[1].bodyBytes));
            _topDishes = decoded1['data']['items'] ?? [];
          }
          
          if (results[2].statusCode == 200) {
            final decoded2 = jsonDecode(utf8.decode(results[2].bodyBytes));
            _heatmapData = decoded2['data']['heatmap'] ?? {};
          }
          
          _isLoading = false;
        });
      }

      debugPrint("✅ Analytics fetched successfully" + "\nTrend: ${_trendData.keys.toList()}" + "\nTop Dishes: ${_topDishes.length}" + "\nHeatmap Issues: ${_heatmapData.keys.toList()}");
    } catch (e) {
      debugPrint("🚨 Lỗi Analytics: $e");
      if (mounted) setState(() => _isLoading = false);
    }
  }

  // ==========================================
  // BIỂU ĐỒ 4 (MỚI): ISSUE BREAKDOWN (Pie Chart)
  // Phân tích xem lỗi nào (Mặn, Nguội, Tệ...) đang chiếm đa số
  // ==========================================
  Widget _buildIssuePieChart() {
    if (_heatmapData.isEmpty) return const Text("Không có dữ liệu chi tiết lỗi.");

    // 1. Gom nhóm đếm tổng số lượng của từng loại Issue
    Map<String, int> totalIssuesCount = {};
    int grandTotal = 0;

    _heatmapData.forEach((dishName, issuesMap) {
      (issuesMap as Map<String, dynamic>).forEach((issueName, count) {
        int issueCount = count as int;
        if (issueCount > 0) {
          totalIssuesCount[issueName] = (totalIssuesCount[issueName] ?? 0) + issueCount;
          grandTotal += issueCount;
        }
      });
    });

    if (grandTotal == 0) return const Text("Chưa có phàn nàn nào được ghi nhận.");

    // 2. Tạo danh sách màu sắc cho Pie Chart
    List<Color> colors = [Colors.redAccent, Colors.orange, Colors.blueAccent, Colors.purple, Colors.teal];
    int colorIndex = 0;

    List<PieChartSectionData> sections = totalIssuesCount.entries.map((entry) {
      final percentage = (entry.value / grandTotal) * 100;
      final color = colors[colorIndex % colors.length];
      colorIndex++;

      return PieChartSectionData(
        color: color,
        value: entry.value.toDouble(),
        title: '${percentage.toStringAsFixed(1)}%',
        radius: 60,
        titleStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: Colors.white),
      );
    }).toList();

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: _bgCard, borderRadius: BorderRadius.circular(16)),
      child: Column(
        children: [
          SizedBox(
            height: 200,
            child: PieChart(
              PieChartData(
                sectionsSpace: 2,
                centerSpaceRadius: 40,
                sections: sections,
              ),
            ),
          ),
          const SizedBox(height: 16),
          // Chú thích (Legend)
          Wrap(
            spacing: 16,
            runSpacing: 8,
            children: totalIssuesCount.entries.map((entry) {
              final color = colors[totalIssuesCount.keys.toList().indexOf(entry.key) % colors.length];
              return Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(width: 12, height: 12, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
                  const SizedBox(width: 6),
                  Text("${entry.key} (${entry.value})", style: TextStyle(color: _textBrown, fontWeight: FontWeight.w600)),
                ],
              );
            }).toList(),
          )
        ],
      ),
    );
  }

  Future<void> _selectDateRange() async {
    final picked = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2020),
      lastDate: DateTime.now(),
      initialDateRange: DateTimeRange(start: _startDate, end: _endDate),
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(colorScheme: ColorScheme.light(primary: _primaryRed)),
          child: child!,
        );
      },
    );

    if (picked != null) {
      setState(() {
        _startDate = picked.start;
        _endDate = picked.end;
      });
      _fetchAllAnalytics(); // Kéo ngày xong thì tự động gọi lại API
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        iconTheme: IconThemeData(color: _primaryRed),
        title: Text("AI Insights", style: TextStyle(color: _textBrown, fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: const Icon(Icons.date_range),
            onPressed: _selectDateRange,
          )
        ],
      ),
      body: Column(
        children: [
          // GLOBAL FILTER DISPLAY
          Container(
            padding: const EdgeInsets.symmetric(vertical: 12),
            width: double.infinity,
            color: _primaryRed.withOpacity(0.05),
            child: Center(
              child: Text(
                "Data from: ${DateFormat('dd MMM yyyy').format(_startDate)} - ${DateFormat('dd MMM yyyy').format(_endDate)}",
                style: TextStyle(color: _primaryRed, fontWeight: FontWeight.w600),
              ),
            ),
          ),
          
          Expanded(
            child: _isLoading 
              ? Center(child: CircularProgressIndicator(color: _primaryRed))
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _buildSectionTitle("Issue Trend"),
                      _buildTrendChart(),
                      const SizedBox(height: 30),

                      _buildSectionTitle("Overview of errors"),
                      _buildIssuePieChart(),
                      const SizedBox(height: 30),

                      _buildSectionTitle("Top Complained Dishes"),
                      _buildTopDishesList(),
                      const SizedBox(height: 30),

                      _buildSectionTitle("Issue Heatmap"),
                      _buildHeatmap(),
                      const SizedBox(height: 40),
                    ],
                  ),
              ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Text(title, style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: _textBrown)),
    );
  }

  // ==========================================
  // BIỂU ĐỒ 1: ISSUE TREND (Line Chart)
  // ==========================================
  Widget _buildTrendChart() {
    if (_trendData.isEmpty) return const Text("No data for this period.");
    
    // Thuật toán: Vì fl_chart cần số (X, Y), ta gán Index(0,1,2..) cho các ngày
    List<LineChartBarData> lines = [];
    int colorIndex = 0;
    List<Color> palette = [Colors.red, Colors.orange, Colors.blue, Colors.purple, Colors.green];

    _trendData.forEach((issueName, pointsList) {
      List<FlSpot> spots = [];
      int xIndex = 0;
      for (var point in pointsList) {
        spots.add(FlSpot(xIndex.toDouble(), (point['count'] as int).toDouble()));
        xIndex++;
      }

      lines.add(
        LineChartBarData(
          spots: spots,
          isCurved: true,
          color: palette[colorIndex % palette.length],
          barWidth: 3,
          isStrokeCapRound: true,
          dotData: const FlDotData(show: false),
          belowBarData: BarAreaData(show: true, color: palette[colorIndex % palette.length].withOpacity(0.1)),
        )
      );
      colorIndex++;
    });

    return Container(
      height: 250,
      padding: const EdgeInsets.only(right: 16, top: 16, bottom: 16),
      decoration: BoxDecoration(color: _bgCard, borderRadius: BorderRadius.circular(16)),
      child: LineChart(
        LineChartData(
          lineBarsData: lines,
          borderData: FlBorderData(show: false),
          gridData: const FlGridData(show: false),
          titlesData: FlTitlesData(
            rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
            topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
            bottomTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)), // Có thể custom hiện ngày ở đây
          ),
        ),
      ),
    );
  }

  // ==========================================
  // BIỂU ĐỒ 2: TOP COMPLAINED (Custom Bars)
  // ==========================================
  Widget _buildTopDishesList() {
    if (_topDishes.isEmpty) return const Text("Great job! No complaints.");

    // Lấy số count lớn nhất để tính tỷ lệ thanh Bar
    int maxCount = _topDishes.fold(0, (max, item) => item['count'] > max ? item['count'] : max);

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: _bgCard, borderRadius: BorderRadius.circular(16)),
      child: Column(
        children: _topDishes.map((dish) {
          double percentage = maxCount > 0 ? (dish['count'] / maxCount) : 0;
          return Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(child: Text(dish['dish_name'] ?? 'Unknown', style: const TextStyle(fontWeight: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis)),
                    Text("${dish['count']} issues", style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold)),
                  ],
                ),
                const SizedBox(height: 6),
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: percentage,
                    backgroundColor: Colors.grey[300],
                    color: _primaryRed,
                    minHeight: 8,
                  ),
                )
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  // ==========================================
  // BIỂU ĐỒ 3 (CẬP NHẬT): HEATMAP CHI TIẾT THEO MÓN
  // ==========================================
  Widget _buildHeatmap() {
    if (_heatmapData.isEmpty) return const Text("Không có chi tiết lỗi theo món.");

    return Column(
      children: _heatmapData.entries.map((entry) {
        String dishName = entry.key; // VD: "Ca kho"
        Map<String, dynamic> issues = entry.value;

        // Nếu món này không có lỗi nào thì bỏ qua
        if (issues.isEmpty) return const SizedBox.shrink();

        return Container(
          width: double.infinity,
          margin: const EdgeInsets.only(bottom: 12),
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: _primaryRed.withOpacity(0.2)),
            boxShadow: [
              BoxShadow(color: Colors.black.withOpacity(0.03), blurRadius: 8, offset: const Offset(0, 2))
            ]
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                "🍲 $dishName", 
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: _textBrown)
              ),
              const Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Divider(),
              ),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: issues.entries.map((issueEntry) {
                  String issueName = issueEntry.key; // VD: "mặn"
                  int count = issueEntry.value as int;
                  
                  if (count == 0) return const SizedBox.shrink();

                  return Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    decoration: BoxDecoration(
                      color: _primaryRed.withOpacity(0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text("Lỗi: ", style: TextStyle(color: _textBrown, fontSize: 13)),
                        Text(
                          issueName.toUpperCase(), 
                          style: TextStyle(color: _primaryRed, fontWeight: FontWeight.bold, fontSize: 13)
                        ),
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(color: _primaryRed, borderRadius: BorderRadius.circular(4)),
                          child: Text(
                            count.toString(), 
                            style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 12)
                          ),
                        )
                      ],
                    ),
                  );
                }).toList(),
              )
            ],
          ),
        );
      }).toList(),
    );
  }
}