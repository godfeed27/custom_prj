# Sony Walkman NW-A105 (hỏng khe microSD) – Ý tưởng project custom

Tài liệu này tổng hợp những gì chiếc NW-A105 có, cái gì còn dùng được khi khe thẻ nhớ
đã hỏng, và danh sách project custom xếp theo độ khó / độ rủi ro để tận dụng tối đa máy.

Nguồn tham khảo nằm ở cuối file. Những chỗ ghi **(chưa xác nhận)** là thông tin
chưa tìm được nguồn chắc chắn, cần tự thử trên máy.

---

## 1. Máy có gì

### Phần cứng

| Thành phần | Chi tiết | Ghi chú cho project |
|---|---|---|
| SoC | NXP i.MX 8M Mini Quad (MIMX8MM6DVTLZAA), 4× Cortex‑A53 @ 1.8 GHz, GPU Vivante GC7000 NanoUltra | Chip công nghiệp, mainline Linux hỗ trợ rất tốt, có tool `uuu` của NXP để flash |
| RAM | 4 GB (theo The Walkman Blog) | Dư cho Termux, proot Debian, emulator nhẹ |
| Bộ nhớ trong | 16 GB eMMC, Android chiếm ~10 GB, còn ~5–6 GB trống | Đây là "thẻ nhớ" duy nhất còn lại |
| Khe microSD | **Hỏng** | Xem mục 2 để bù |
| Màn hình | 3.6" cảm ứng, 1280×720 | Đủ cho dashboard / now‑playing / mini kiosk |
| Âm thanh | Ampli số S‑Master HX, DSEE HX, jack 3.5 mm unbalanced, 35 mW + 35 mW @ 16 Ω | Lý do chính để giữ máy |
| Định dạng | MP3, WMA, AAC, FLAC, ALAC, AIFF, PCM, DSD (chuyển sang PCM) | |
| Bluetooth | 5.0, phát LDAC / aptX HD / aptX / AAC / SBC, **không có chế độ nhận (receiver)** | Chỉ làm nguồn phát BT |
| Wi‑Fi | Có (dùng được cho streaming, NAS, SSH) | Thay thế hoàn toàn thẻ nhớ nếu có mạng |
| NFC | Chỉ dùng ghép đôi 1 chạm với tai nghe/loa Sony | Không dùng được cho HCE/đọc thẻ |
| USB‑C | Sạc + MTP/ADB. **Không có chế độ USB DAC.** USB host/OTG: **(chưa xác nhận)** | Cần tự thử OTG, xem mục 2 |
| Nút cứng | Play, FF, REW, Vol +/−, nguồn, công tắc Hold | Remap được, dùng cho project không cần màn hình |
| Pin | ~26 h nghe MP3 offline, ít hơn nhiều khi stream Wi‑Fi | |
| Không có | Mic, camera, GPS, SIM, loa ngoài | Không làm trợ lý giọng nói / intercom được |

### Phần mềm

| Mục | Chi tiết |
|---|---|
| OS | Android 9, gần như stock, có Google Play. Firmware cuối: 4.06.00 (30/11/2021), Sony không cập nhật nữa |
| App Sony | "Music player" với DSEE HX, Vinyl Processor, EQ 10 băng, DC Phase Linearizer, Dynamic Normalizer |
| Bootloader | **Unlock được** bằng `fastboot oem unlock` (hoặc `uuu FB: oem unlock` trên Windows). Phân vùng A/B |
| Root | **Có**: tắt AVB bằng `blank_vbmeta.img`, rồi flash kernel KernelSU hoặc boot.img vá bằng APatch. **Magisk không chạy** trên máy này (magiskinit không được gọi, issue #7091) |
| Kernel source | Sony công bố GPL tại oss.sony.net (NW‑A105_Ver20211130). Repo cộng đồng có sẵn cây `kernel_imx`, `walkman.config`, `dtbo_a100.img`, `blank_vbmeta.img` |
| Firmware | File `.UPG` mã hoá AES‑CBC, **khoá nằm ngay trên máy** ở `/vendor/usr/data/icx_nvp.cfg`. Có tool `nwwmdecrypt.jar` + `payload_dumper` để bung. Có sẵn gói fastboot firmware A100 4.06 quốc tế để cứu máy |
| Vào fastboot | Giữ **Vol− + FF** khi bật nguồn. Vào recovery: bấm Vol+ và nguồn 2–5 lần |
| Vùng (destination) | Đổi bằng `nvpflag` sau khi root (bỏ giới hạn âm lượng EU trên ZX500; trên A100 chưa ai xác nhận) |

---

## 2. Bù cho khe thẻ nhớ hỏng

Thứ tự ưu tiên, từ dễ đến khó:

1. **Stream qua Wi‑Fi** – Spotify, Apple Music, Tidal, Qobuz, YouTube Music chạy trực tiếp.
   DSEE HX / EQ của Sony áp dụng hệ thống nên nhạc stream vẫn qua đường xử lý của Walkman.
2. **Phát nhạc từ NAS / PC trong nhà** – không cần copy gì vào máy:
   - Navidrome / Jellyfin / Plex trên PC hoặc NAS → app Symfonium, Finamp, Ultrasonic, DSub trên Walkman.
   - Chia sẻ SMB / DLNA → USB Audio Player PRO, Neutron, BubbleUPnP, Hi‑Fi Cast.
   - Symfonium và UAPP có cache offline, chọn vài GB "nhạc hay nghe" để tải xuống bộ nhớ trong.
3. **Tự đồng bộ bộ nhớ trong** – Syncthing‑Fork hoặc FolderSync (SMB/WebDAV/Drive) đồng bộ
   một thư mục ~5 GB "playlist tuần này" từ PC sang máy, không cần dây, không cần thẻ.
4. **USB‑C OTG (chưa xác nhận)** – cắm USB‑C flash drive, nếu Android hiện thông báo
   "USB drive" là dùng được, thẻ nhớ coi như thay bằng USB. Cách kiểm tra:
   ```
   adb shell ls /sys/bus/usb/devices/
   adb shell dumpsys usb
   ```
   Nếu không nhận, có thể thử lại sau khi root (bật host mode trong kernel) – đây là một project riêng.
5. **Sửa phần cứng** – socket microSD push‑push là linh kiện phổ thông, tiệm sửa điện thoại
   có máy khò thay được. Chưa có hướng dẫn tháo máy chính thức (iFixit chưa có NW‑A105),
   nên chụp ảnh từng bước nếu tự làm.

---

## 3. Danh sách project theo cấp độ

### Cấp 0 – Không root, không rủi ro

| # | Project | Tận dụng | Cách làm tóm tắt |
|---|---|---|---|
| 0.1 | **Máy nghe nhạc mạng (NAS player)** | Wi‑Fi, S‑Master HX, DSEE HX | Dựng Navidrome trên PC/NAS → cài Symfonium. Toàn bộ thư viện FLAC nằm ở nhà, Walkman chỉ là "đầu phát" |
| 0.2 | **Streamer cho dàn âm thanh ở nhà** | Jack 3.5 mm, Wi‑Fi, cắm sạc liên tục | Cắm 3.5 mm → ampli. Cài AirReceiver (AirPlay/DLNA receiver), bật Spotify Connect / Tidal Connect. Điều khiển từ điện thoại. Thay thế được chế độ USB DAC bị thiếu: PC → cast qua Wi‑Fi → Walkman → ampli. Bật "Battery Care" (nếu máy có) để không chai pin |
| 0.3 | **Nguồn phát Bluetooth LDAC** | BT 5.0 LDAC/aptX HD, NFC | Dùng làm nguồn phát hi‑res cho tai nghe/loa BT, xe hơi. NFC chạm để pair với tai nghe Sony |
| 0.4 | **Podcast / audiobook player chuyên dụng** | Bộ nhớ trong nhỏ vẫn đủ | AntennaPod, Smart AudioBook Player; file nhỏ, tải theo tập |
| 0.5 | **Đồng bộ nhạc không dây** | Wi‑Fi, bộ nhớ trong | Syncthing‑Fork: PC push playlist ~5 GB sang máy, xoá tự động khi đổi playlist |
| 0.6 | **Màn hình bàn làm việc / Now Playing / Home Assistant** | Màn 3.6" 720p, Wi‑Fi | Fully Kiosk Browser mở dashboard Home Assistant, hoặc app HA Companion. Kết hợp 0.2: vừa phát nhạc vừa hiện bìa album |
| 0.7 | **Termux – máy chủ bỏ túi** | 4 nhân A53, 4 GB RAM | Termux (bản F‑Droid), cài SSH, Python, Node, Git. Dùng làm node Syncthing, chạy script, thử nghiệm ARM64. Có thể cài Debian qua `proot-distro` không cần root |
| 0.8 | **Remap nút cứng** | Nút FF/REW/Play | Button Mapper: nhấn giữ FF = bài tiếp playlist, giữ Play = bật tắt DSEE, v.v. Dùng máy trong túi không cần nhìn màn |
| 0.9 | **Máy chơi game retro mini** | GPU GC7000, màn 720p, BT | RetroArch / emulator GBA, SNES, PS1 + tay cầm Bluetooth. Chỉ là mục "cho vui" |

### Cấp 1 – Cần ADB, chưa root (rủi ro thấp, hoàn tác được)

| # | Project | Cách làm |
|---|---|---|
| 1.1 | **Debloat giải phóng bộ nhớ + pin** | `adb shell pm uninstall -k --user 0 <package>` cho app Google/Sony không dùng. Hoàn tác bằng `pm install-existing`. Mỗi GB lấy lại được là thêm nhạc offline |
| 1.2 | **Tối ưu giao diện cho màn nhỏ** | `adb shell wm density`, tắt animation, launcher nhẹ (Lawnchair/Niagara), dark mode toàn hệ thống |
| 1.3 | **Kiểm tra OTG & USB audio** | Thử flash drive USB‑C, DAC USB; đọc `dumpsys usb`. Kết quả quyết định có cần đi tiếp sang cấp 2 không |
| 1.4 | **Bung firmware để nghiên cứu** | Lấy khoá: `adb shell cat /vendor/usr/data/icx_nvp.cfg` (mục NAS) → `java -jar nwwmdecrypt.jar -i <upg> -o <zip> -k <key>` → `payload_dumper`. Có boot.img, system.img, vendor.img gốc để backup và vá |

### Cấp 2 – Root (unlock bootloader, **xoá sạch dữ liệu**, có firmware cứu máy)

| # | Project | Tận dụng | Ghi chú |
|---|---|---|---|
| 2.1 | **Root bằng KernelSU hoặc APatch** | Toàn bộ hệ thống | Xem quy trình ở mục 5. Không dùng Magisk |
| 2.2 | **EQ/convolver hệ thống (AutoEq cho tai nghe của bạn)** | S‑Master HX + DSP | JamesDSP hoặc Viper4Android (Android 9 cần root). Nạp profile AutoEq của đúng model tai nghe → hiệu quả hơn EQ 10 băng của Sony |
| 2.3 | **Chặn quảng cáo / theo dõi toàn hệ thống** | Wi‑Fi | AdAway (hosts) hoặc AdGuard. Giảm dữ liệu nền, tăng pin khi stream |
| 2.4 | **Kernel tự build tối ưu pin** | SoC | Repo cộng đồng đã vá kernel: thêm mức xung thấp hơn + governor tiết kiệm điện + KernelSU. Build lại từ source Sony với `walkman.config`, thử thêm bật USB host nếu 1.3 thất bại |
| 2.5 | **Đổi mã vùng (destination)** | Ampli | `nvpflag shp 0x00000006 0x00000000` rồi `nvpflag sid 0x00000000` → vùng E. Trên ZX500 bỏ giới hạn âm lượng EU; trên A100 chưa ai xác nhận, chỉ thử nếu máy là bản EU bị khoá âm lượng |
| 2.6 | **Debloat sâu + ROM "sạch"** | Bộ nhớ, pin | Xoá hẳn app hệ thống, hoặc flash bản firmware Trung Quốc 4.04 (không Google, pin tốt hơn – mới có gói cho ZX500, A100 cần tự bung) |
| 2.7 | **Debian chroot thực sự** | CPU/RAM | Chroot thay vì proot: nhanh hơn, chạy được dịch vụ bind cổng thấp (DNS, web). Walkman thành máy chủ ARM64 nhỏ chạy 24/7 cắm sạc |

### Cấp 3 – Hardcore (chưa ai công bố làm được trên A105, cần nhiều thời gian)

| # | Project | Vì sao khả thi | Rủi ro / việc phải làm |
|---|---|---|---|
| 3.1 | **Flash GSI (Android 10–13 AOSP/LineageOS)** | Máy ra đời với Android 9 nên phải tuân Treble; bootloader đã unlock; A/B không dynamic partition nên flash thẳng `system_a` | Chưa thấy ai thử. Khả năng cao mất DSEE HX, EQ Sony, có thể mất cả âm thanh nếu audio HAL vendor không khớp. Luôn giữ system.img gốc |
| 3.2 | **Mainline Linux / postmarketOS trên Walkman** | i.MX 8M Mini được mainline hỗ trợ đầy đủ (EVK của NXP). Có kernel source Sony + `dtbo_a100.img` để viết device tree | Driver S‑Master HX (họ `icx_*` trong `kernel_imx`) là của Sony, phải port. Mục tiêu cuối: Walkman chạy MPD/Squeezelite/Snapcast client như một "Volumio" bỏ túi |
| 3.3 | **Thay socket microSD** | Linh kiện phổ thông, máy khò | Chưa có tài liệu tháo máy; màn hình dán keo, dễ hỏng cáp |
| 3.4 | **Mod USB host bằng kernel** | i.MX 8M Mini có USB dual‑role | Nếu Sony tắt host mode trong DT/kernel config, bật lại và build; nếu thiếu cấp nguồn VBUS trên mạch thì chỉ dùng được hub OTG có nguồn ngoài |

---

## 4. Lộ trình đề xuất

1. **Tuần 1 – Không root**: dựng Navidrome (hoặc Jellyfin) trên PC, cài Symfonium, Syncthing‑Fork,
   Termux. Thử OTG flash drive. Lúc này máy đã dùng được 100% cho nghe nhạc mà không cần thẻ.
2. **Tuần 2 – ADB**: debloat, bung firmware 4.06 bằng khoá trên máy để có bộ ảnh gốc (backup).
3. **Tuần 3 – Root**: unlock, tắt AVB, flash kernel KernelSU (hoặc APatch). Cài JamesDSP + AutoEq.
   Đây là điểm "lời" nhất về chất âm.
4. **Sau đó**: chọn 1 trong 3 hướng dài hơi: kernel tự build (pin, USB host), GSI, hoặc mainline Linux.

Nếu chỉ chọn **một** project: làm 0.2 + 2.2 (streamer cho dàn ở nhà + AutoEq). Nó dùng Wi‑Fi,
S‑Master HX, màn hình, pin, nút cứng và không bị ảnh hưởng gì bởi khe thẻ hỏng.

---

## 5. Quy trình root tóm tắt (theo repo cộng đồng)

> Xoá sạch dữ liệu. Làm sai bước AVB có thể bootloop; đã có gói fastboot firmware để cứu.
> Trên Windows dùng `uuu` của NXP thay `fastboot`; nhớ thêm hậu tố slot `_a`/`_b`.

```
# 0. Bật Developer options → OEM unlocking + USB debugging
adb shell getprop ro.boot.slot_suffix        # ghi nhớ _a hoặc _b (cho uuu)

# 1. Unlock bootloader (máy "đơ" ~500 giây vì đang xoá userdata)
adb reboot bootloader
fastboot oem unlock                          # Windows: uuu FB: oem unlock
fastboot reboot                              # Windows: uuu FB: reboot

# 2. Tắt AVB bằng vbmeta trống (sau đó máy bootloop → vào recovery → factory reset)
fastboot --disable-verity --disable-verification flash vbmeta blank_vbmeta.img
# Windows: uuu FB: flash vbmeta_a blank_vbmeta.img   (hoặc vbmeta_b)

# 3a. Flash kernel có KernelSU (bản build cho A100)
fastboot flash boot boot-a100.img            # Windows: uuu FB: flash boot_a boot-a100.img
# 3b. Hoặc: vá boot.img gốc (bung từ firmware) bằng app APatch rồi flash tương tự

# 4. Cài app KernelSU / APatch, cấp quyền su cho shell, kiểm tra:
adb shell su -c id
```

Cứu máy: giữ **Vol− + FF** khi bật nguồn để vào fastboot, flash lại bộ ảnh gốc
(boot, vbmeta, system, vendor) từ gói "NW‑A100 series 4.06 International".

---

## 6. Nguồn tham khảo

- Thông số, SoC, RAM: [The Walkman Blog – What SoC is Sony using](https://thewalkmanblog.blogspot.com/2019/10/what-soc-is-sony-using.html),
  [Headphone Zone – NW‑A105](https://www.headphonezone.in/products/sony-nw-a105),
  [Gizmodo review](https://gizmodo.com/sonys-first-android-powered-walkman-is-damn-compelling-1841209009)
- Không có BT receiver: [Neofiliac – NW‑A100 series](https://neofiliac.com/product/1174/sony-nw-a100-series-nw-a105-amp-nw-a100tps-walkman)
- Không có USB DAC: [Audio46 – NW‑A105](https://audio46.com/products/sony-nw-a105-walkman-digital-audio-player)
- Firmware cuối 4.06.00: [Sony support downloads](https://www.sony.ca/en/electronics/support/digital-music-players-nw-nwz-a-series/nw-a105/downloads)
- Unlock, AVB, KernelSU kernel, giải mã firmware, nvpflag: [97lily/2019_android_walkman](https://github.com/97lily/2019_android_walkman) (fork của notcbw)
- Root bằng APatch cho A100 (tiếng Trung): [Sikz1218/unlock-and-root_android_walkman_A100-series](https://github.com/Sikz1218/unlock-and-root_android_walkman_A100-series)
- Magisk không chạy: [topjohnwu/Magisk issue #7091](https://github.com/topjohnwu/Magisk/issues/7091)
- Thảo luận root, kernel build: [XDA – Help to root Sony Walkman NW‑A105](https://xdaforums.com/t/q-root-newbie-help-to-root-sony-walkman-nw-a105-kernel-build-achived.4151005/)
- Kernel GPL của Sony: [oss.sony.net – NW‑A105_Ver20211130](https://oss.sony.net/Products/Linux/Audio/NW-A105_Ver20211130.html)
- Tool flash của NXP: [nxp-imx/mfgtools (uuu)](https://github.com/nxp-imx/mfgtools)
- Help Guide chính thức: [helpguide.sony.net – NW‑A100 series](https://helpguide.sony.net/dmp/nwa100/v1/en/index.html)

---

## 7. Chạy AI nhỏ và dùng làm "bộ xử lý chuyên biệt"

### Giới hạn phần cứng cần biết trước

| Yếu tố | Thực tế trên NW‑A105 | Hệ quả |
|---|---|---|
| CPU | 4× Cortex‑A53 @ 1.8 GHz, ARMv8.0, có NEON nhưng **không có lệnh dot‑product (SDOT) / i8mm** | Suy luận int8/int4 chậm hơn nhiều so với A55/A76 cùng xung; tương đương Raspberry Pi 3B+ nhanh hơn ~30 % |
| GPU | GC NanoUltra: chỉ OpenGL ES 2.0 / OpenVG. **Không OpenCL, không Vulkan compute** | Mọi mô hình chạy thuần CPU. Các app dùng GPU (MLC Chat, llama.cpp Vulkan) không dùng được |
| NPU | **Không có** (chỉ i.MX 8M Plus mới có NPU) | |
| RAM | 4 GB, Android chiếm ~1.5 GB | Mô hình tối đa thực tế ~2 GB file → LLM ≤ 3B ở Q4 |
| Băng thông RAM | LPDDR4 bus 32‑bit, thấp hơn điện thoại cùng thời | Tốc độ sinh token bị giới hạn bởi băng thông, không phải số nhân |
| Coprocessor | Cortex‑M4F @ 400 MHz trong SoC | Lõi real‑time, Sony không mở cho người dùng; cần kernel/remoteproc riêng (cấp 3) |
| VPU | Giải mã H.264/H.265 1080p60, mã hoá H.264 1080p | Phát video mượt, nhưng không dùng cho AI |
| Không có mic / camera | | Không làm trợ lý giọng nói, không nhận diện ảnh trực tiếp |

### LLM: chạy được, nhưng chậm

Ước lượng từ kết quả llama.cpp trên Raspberry Pi 3/4 (cùng lớp A53/A72), **chưa đo trên máy thật**:

| Mô hình (Q4_K_M) | Kích thước | Sinh token | Nạp prompt | Dùng được cho |
|---|---|---|---|---|
| Qwen2.5‑0.5B, SmolLM2‑360M | 0.4 GB | ~5–8 tok/s | ~15–25 tok/s | Chat ngắn, tóm tắt vài câu, sửa chính tả |
| Llama 3.2 1B, Qwen2.5 1.5B, SmolLM2‑1.7B | 0.8–1.1 GB | ~2–4 tok/s | ~6–12 tok/s | Chat chậm, trả lời câu hỏi đơn giản |
| Qwen2.5 3B, Llama 3.2 3B, Phi‑3‑mini | ~2 GB | ~1 tok/s | ~2–4 tok/s | Chỉ để thử, prompt 500 token mất vài phút |

Cách chạy: Termux → `pkg install cmake clang git` → build `llama.cpp` (bật `-DGGML_NATIVE=ON`) → `llama-server` chạy nền, dùng giao diện web qua Wi‑Fi từ điện thoại/PC.
App có sẵn: PocketPal AI, ChatterUI (đều dùng llama.cpp CPU, cần kiểm tra có hỗ trợ Android 9 không).

Kết luận: LLM trên máy này là **trò chơi học tập**, không phải công cụ. Nếu cần LLM thật, để máy làm **client**: Termux hoặc app chat gọi tới PC/API qua Wi‑Fi.

### AI nhỏ chạy tốt và hợp với bản chất "máy nghe nhạc"

| # | Project | Mô hình | Vì sao hợp |
|---|---|---|---|
| 7.1 | **Máy đọc sách / báo bằng giọng nói offline** | Piper TTS (VITS) qua sherpa‑onnx, ~20–60 MB/giọng, có tiếng Việt | Piper chạy real‑time thoải mái trên A53. Đầu ra đi qua S‑Master HX → giọng đọc nghe hay hơn mọi điện thoại. Cài sherpa‑onnx TTS engine APK làm TTS hệ thống → app đọc EPUB/RSS (Moon+ Reader, @Voice) dùng được ngay |
| 7.2 | **Trạm chép lời podcast / ghi âm** | whisper.cpp `tiny` / `base`, hoặc Vosk | Không có mic nên chỉ chép từ file. `tiny` chậm hơn real‑time ~2–4 lần trên A53: 1 giờ podcast mất 2–4 giờ chạy nền khi cắm sạc. Vosk nhẹ hơn, gần real‑time |
| 7.3 | **Gắn tag / phân loại nhạc tự động** | Essentia‑TensorFlow (MusiCNN, mood, genre), chromaprint + AcoustID | Chạy một lần qua thư viện offline, sinh playlist theo mood/genre. Nặng vừa phải, chạy nền qua đêm |
| 7.4 | **Tìm kiếm ngữ nghĩa lời bài hát / ghi chú** | all‑MiniLM‑L6‑v2 (22M tham số) qua ONNX Runtime | Embedding 1 câu mất vài chục ms; đủ để tìm "bài nào nói về mưa" trong thư viện lyrics |
| 7.5 | **Nhận diện bài hát offline** | Fingerprint (chromaprint / dejavu) | Không cần mạng, nhưng chỉ nhận trong thư viện của bạn vì không có mic → phải đưa file vào |
| 7.6 | **Tách stem / upscale nhạc bằng AI** | Demucs, Spleeter | **Không khuyến nghị**: trên A53 mất hàng giờ cho một bài và dễ hết RAM. Làm trên PC rồi đồng bộ sang máy |

### Dùng làm bộ xử lý chuyên biệt (không nhất thiết AI)

| # | Vai trò | Cách dựng | Ghi chú |
|---|---|---|---|
| 7.7 | **Endpoint multiroom audio (Snapcast / Squeezelite / Roon Bridge‑lite)** | Termux: `pkg install pulseaudio` (sink OpenSL ES) → chạy `snapclient` hoặc `squeezelite -o pulse` | Walkman thành một "loa" trong hệ thống nhiều phòng, âm qua S‑Master HX ra ampli. Rất hợp với mục 0.2 |
| 7.8 | **Máy chủ TTS cho Home Assistant (Wyoming‑Piper)** | proot Debian → `pip install wyoming-piper` → HA trỏ tới IP Walkman | Walkman gánh phần sinh giọng nói cho cả nhà; HA gửi text, nhận WAV. Không cần mic trên Walkman |
| 7.9 | **DNS lọc quảng cáo cho cả nhà (AdGuard Home)** | Cần root để bind cổng 53, hoặc không root + router chuyển cổng 53 → 5353 | Chạy 24/7 cắm sạc, tải nhẹ, 4 GB RAM dư |
| 7.10 | **Node Syncthing / backup nhỏ** | Syncthing‑Fork, bộ nhớ trong ~5 GB | Node trung gian luôn bật để ảnh/ghi chú điện thoại đồng bộ về PC |
| 7.11 | **BLE beacon / cảm biến hiện diện** | App HA Companion phát iBeacon, hoặc Termux + `bluetoothctl` quét BLE | Cắm cố định một phòng để HA biết điện thoại nào đang ở phòng đó |
| 7.12 | **Bộ xử lý DSP tai nghe** | JamesDSP (root): convolver, AutoEq, crossfeed, Dynamic Range Compressor | Đây chính là "bộ xử lý chuyên biệt" đúng nghĩa nhất với phần cứng của máy |
| 7.13 | **Máy chạy thử nghiệm ARM64 / CI nhỏ** | Termux + SSH, hoặc chroot Debian (root) | Build/test package cho ARMv8.0 (không dotprod) – hữu ích khi cần kiểm tra tương thích với Pi 3 / Zero 2 W |

### Nên chọn gì

- Muốn "AI" thực sự có ích hằng ngày: **7.1 (Piper TTS đọc sách)** + **7.12 (JamesDSP)**. Cả hai nhẹ, chạy offline, tận dụng đúng điểm mạnh âm thanh của máy.
- Muốn một hộp hạ tầng luôn bật: **7.7 (Snapcast endpoint)** hoặc **7.8 (Wyoming‑Piper cho HA)**.
- Muốn nghịch LLM: Qwen2.5‑0.5B hoặc Llama 3.2 1B trong Termux, chấp nhận 2–8 token/giây.

Nguồn thêm cho mục này:
[Datasheet i.MX 8M Mini (NXP)](https://www.nxp.com/docs/en/data-sheet/IMX8MMIEC.pdf),
[Benchmark LLM trên Raspberry Pi 5](https://tinyweights.dev/posts/run-llms-raspberry-pi-5/),
[sherpa‑onnx TTS engine APK](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html),
[Piper + sherpa‑onnx trên thiết bị](https://medium.com/@patare.vivek/running-neural-text-to-speech-on-device-with-piper-and-sherpa-onnx-58f4eed29247),
[Termux PulseAudio OpenSL ES sink](https://github.com/termux/termux-packages/pull/6290).
