class LocalBank {
  final String displayName; // Tên hiển thị đẹp trên UI (VD: Vietcombank (VCB))
  final String apiEnum;     // Tên chuỗi gửi lên Backend (Phải khớp 100% Swagger)
  final String code;        // Mã BIN code gửi lên Backend

  const LocalBank({
    required this.displayName,
    required this.apiEnum,
    required this.code,
  });
}

// Danh sách đã được Tech Lead tối ưu & bổ sung đầy đủ theo Enum của Backend
final List<LocalBank> vietnamBanks = [
  const LocalBank(displayName: "Vietcombank (VCB)", apiEnum: "Vietcombank", code: "970436"),
  const LocalBank(displayName: "Techcombank (TCB)", apiEnum: "Techcombank", code: "970422"),
  const LocalBank(displayName: "VPBank", apiEnum: "VPBank", code: "970432"), // Đã sửa lại BIN chuẩn của VPBank
  const LocalBank(displayName: "BIDV", apiEnum: "BIDV", code: "970418"),     // Đã sửa lại BIN chuẩn của BIDV
  const LocalBank(displayName: "Agribank", apiEnum: "Agribank", code: "970405"),
  const LocalBank(displayName: "MBBank (MB)", apiEnum: "MBBank", code: "970422"),
  const LocalBank(displayName: "ACB", apiEnum: "ACB", code: "970416"),
  const LocalBank(displayName: "Sacombank", apiEnum: "Sacombank", code: "970403"),
  const LocalBank(displayName: "VietinBank", apiEnum: "VietinBank", code: "970415"),
  const LocalBank(displayName: "TPBank", apiEnum: "TPBank", code: "970423"),
  const LocalBank(displayName: "HDBank", apiEnum: "HDBank", code: "970437"),
  const LocalBank(displayName: "VIB", apiEnum: "VIB", code: "970441"),
  
  // --- CÁC NGÂN HÀNG BỔ SUNG CHO KHỚP SWAGGER ---
  const LocalBank(displayName: "SHB", apiEnum: "SHB", code: "970443"),
  const LocalBank(displayName: "OCB", apiEnum: "OCB", code: "970448"),
  const LocalBank(displayName: "MSB", apiEnum: "MSB", code: "970426"),
  const LocalBank(displayName: "LienVietPostBank (LPBank)", apiEnum: "LienVietPostBank", code: "970449"),
  const LocalBank(displayName: "SeABank", apiEnum: "SeABank", code: "970440"),
  const LocalBank(displayName: "BacABank", apiEnum: "BacABank", code: "970409"),
  const LocalBank(displayName: "PVComBank", apiEnum: "PVComBank", code: "970412"),
  const LocalBank(displayName: "KienLongBank", apiEnum: "KienLongBank", code: "970452"),
  const LocalBank(displayName: "NCB", apiEnum: "NCB", code: "970419"),
];