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
| Wi‑Fi | 802.11a/b/g/n/ac, 2.4 + 5 GHz. Chip Qualcomm QCA9377 (driver `qcacld-2.0_sony`; module Murata Type 1PJ theo trích đoạn; kiểm bằng `adb shell lsmod`, vendor SDIO 0x0271) | Thay thế hoàn toàn thẻ nhớ nếu có mạng. Ưu tiên 5 GHz |
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
   Hiệu ứng Sony (EQ, DSEE HX, ClearAudio+…) nằm trong HAL nên áp lên mọi app nếu đang bật; *Direct Source* bỏ qua hết.
   Lưu ý công tắc *Settings › Sound › High‑Res streaming*: tắt thì app bên thứ ba bị hạ về 48 kHz/16‑bit,
   bật thì bị nâng lên 192 kHz/32‑bit (xem [docs/a105-wifi-dac.md](docs/a105-wifi-dac.md) mục 5).
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
| 0.2 | **Streamer cho dàn âm thanh ở nhà** | Jack 3.5 mm, Wi‑Fi, cắm sạc liên tục | Cắm 3.5 mm → ampli. Spotify Connect chạy sẵn (app Spotify trên A105). Âm thanh từ Mac: thử AirPlay Receiver của jqssun, hoặc Airfoil Satellite, roc‑droid, SonoBus. Thay thế được chế độ USB DAC bị thiếu: Mac → Wi‑Fi → Walkman → ampli. Chi tiết và bản tự xây có điều khiển + màn hình thông số: mục 9 và [docs/a105-wifi-dac.md](docs/a105-wifi-dac.md). Pin: xem mục 9 của tài liệu đó |
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

---

## 8. Hardcore: bản đồ phần cứng từ device tree và các project đỉnh

Mục này dựa trên **kernel source GPL của Sony** trong repo cộng đồng
(`kernel_imx/arch/arm64/boot/dts/sony/sony-imx8mm-dmp1.dts`, các overlay `icx1293`/`icx1295`,
và `kernel_imx/walkman.config`). Đây là bằng chứng trực tiếp về những gì bo mạch có, không phải suy đoán từ marketing.

### 8.1 Bo mạch thật sự có gì (từ DTS + kernel config)

| Khối | Linh kiện / cấu hình trong DTS | Ý nghĩa cho project |
|---|---|---|
| Board ID | `ICX1293` = dòng A100 (pin 1285 mAh), `ICX1295` = dòng ZX500 (pin 1500 mAh, có thêm FPGA Lattice LIF‑MD6000 "RME" = DSD Remastering Engine). Cả hai dùng chung `sony-imx8mm-dmp1.dts` | A105 của bạn là ICX1293. Phần mềm A100 và ZX500 gần như giống nhau, chỉ khác overlay |
| USB‑C | `usbotg1` với `dr_mode = "otg"`, Type‑C controller **FUSB303D** (`port-role = "drp-try.snk"`, cấp 500 mA), VBUS 5 V lấy từ **boost của sạc BQ25898** qua chân `OTG_EN` (GPIO4_IO19). Kernel bật `USB_CHIPIDEA_HOST`, `USB_STORAGE`, `SND_USB_AUDIO`, `USB_HID` | **USB host (OTG) được thiết kế sẵn trong phần cứng lẫn kernel.** Flash drive, DAC USB, bàn phím USB‑C có cơ sở để chạy. Nếu Android không nhận là do vendor config, không phải do phần cứng |
| USB gadget | `USB_CONFIGFS=y`, `USB_F_UAC2=m` (có module UAC2 nhưng Sony không bật `USB_CONFIGFS_F_UAC2`), có MTP/ADB/RNDIS/NCM/MIDI/HID | **USB DAC mode (Sony đã bỏ) có thể phục hồi bằng kernel tự build** |
| microSD | `usdhc2`, card‑detect qua GPIO5_IO3 (A100: active‑low), có các trạng thái pinctrl `cd_pullup/pulldown/none`, thuộc tính Sony `svs,icx-cd-gpios`, `svs,icx-cd-wake` | **Nếu chỉ hỏng công tắc card‑detect**, sửa được bằng DTS (`broken-cd`), xem 8.3 |
| eMMC | `usdhc3`, 8‑bit, HS400, bảng drive‑strength cho Toshiba / Hynix / Samsung | eMMC chuẩn BGA; thay eMMC lớn hơn là khả thi về nguyên tắc (8.6) |
| Âm thanh | Codec **CXD3778GF** (S‑Master HX) trên I2C 0x4e, nhận dữ liệu qua **SAI3** với hai pinctrl `hi_res` và `dsd` (`fsl,sai-multi-lane`, DSD dataline), hai thạch anh 44.1k/48k riêng (`osc_fs441_en`, `osc_fs480_en`), GPIO mute SE/BTL, nguồn BTL 5 V/7 V; SAI5 "guidance"; MICFIL (PDM mic in) | Driver ASoC **có source** (`sound/soc/codecs/cxd3778gf/`: `_table.c` bảng tuning, `_dnc.c` chống ồn, `_regmon.c` theo dõi thanh ghi). Đây là chìa khoá cho mod âm thanh và port mainline |
| Lõi M4 | `m4_reserved` RAM tại 0x80000000, `&mu` + `&rpmsg` bật, node `sony,imx8mm-rpmsg-i2s` ("audio device in M4 domain"), `CONFIG_ICX_SILENT_LPA_LOG` | **Cortex‑M4 đang chạy firmware Low‑Power Audio của Sony**: A53 ngủ, M4 bơm PCM ra SAI. Đó là lý do pin 26 giờ. Có thể thay bằng firmware tự viết (8.5) |
| MCU phụ | NXP **Kinetis MKL17Z32** (Cortex‑M0+) trên I2C 0x10, có chân `ucon_xfwupdate`, `ucon_req`, `ucon_xreset` | Vi điều khiển "ucon" của Sony, cập nhật firmware được từ A53. Chức năng chưa rõ (nghi quản lý nguồn/jack/DNC) – mục tiêu RE |
| NFC | **NXP PN7150** (NCI controller đầy đủ) trên I2C 0x28 | Không phải tag thụ động: đọc/ghi thẻ NFC được nếu có stack (Android NFC hoặc Linux `nxp-nci` + neard) |
| Wi‑Fi / BT | Qualcomm **QCA9377** (DTS không ghi tên chip; suy từ driver riêng của Sony `qcacld-2.0_sony` (module `wlan`), bảng ID SDIO và firmware `qwlan30.bin`; tên module Murata Type 1PJ theo trích đoạn): Wi‑Fi SDIO trên `usdhc1` (chân `WLAN_EN`), BT qua `uart1` HCI‑UART (QCA). `CONFIG_BRCMFMAC=m` cũng được build nhưng DTS không có node nào dùng, chỉ là phần thừa từ BSP của NXP | Mainline: có thể dùng `ath10k_sdio` (suy luận, cần thử). Wi‑Fi và BT dùng chung ăng‑ten, chia thời gian (trích đoạn Qualcomm); chưa rõ 5 GHz có tránh được hay không |
| UART console | `uart2` là `stdout-path` (console u‑boot/kernel) | **Có cổng debug trên PCB**, chỉ cần tìm test‑pad |
| Màn hình / cảm ứng | Panel MIPI‑DSI **Himax HX83102D** 720×1280 (40×67 mm) qua LCDIF + NWL DSI, backlight PWM; cảm ứng Himax (`himax,hxcommon`) qua **SPI** | Mainline có `panel-himax-hx83102` (cần thêm chuỗi init từ driver Sony) |
| Nguồn | PMIC ROHM **BD71837/BD71840**, sạc **BQ25898** (driver Sony `bq25898-icx`), đo pin **MAX1704x** (bảng model pin trong DTS), 2 vị trí gia tốc kế **BMA422** | PMIC + gauge có driver mainline; BQ25898 chưa có (ID khác bq25890), FUSB303 chưa có |
| Nút cứng | `gpio-keys`: play, vol±, ff, fr đều `wakeup-source`; `sony,hold_switch` | Mainline dùng lại được ngay |
| Thừa từ EVK | camera OV5640, FlexSPI NOR (disabled), `gps_ctl`/uart3, PCIe pinctrl | Không có trên máy, bỏ qua |

### 8.2 Năm project hardcore, xếp theo "lời / công"

| # | Project | Lời được gì | Việc phải làm | Rủi ro |
|---|---|---|---|---|
| H1 | **USB DAC mode bằng kernel** (gadget UAC2) | Walkman thành card âm thanh USB cho PC/điện thoại: PC → USB‑C → S‑Master HX → tai nghe. Tính năng Sony đã bỏ | Build kernel với `CONFIG_USB_CONFIGFS_F_UAC2=y`; tạo function `uac2.0` trong configfs cạnh `mtp`/`ffs`; viết daemon nhỏ (tinyalsa) đọc PCM từ gadget và đẩy vào AudioFlinger (hoặc Termux `arecord | pacat`) để vẫn đi qua DSEE/EQ. Đã có đề án cộng đồng He0xD4C0/A100_ZX500_USB-DAC (2026, chưa kiểm chứng). Lưu ý kernel 4.14: `f_uac2` chỉ một rate cố định và không có feedback endpoint, nên trôi đồng hồ gây tiếng click | Thấp: chỉ kernel + userland, hoàn tác bằng flash lại boot |
| H2 | **Bluetooth receiver (A2DP sink)** | Điện thoại phát BT → Walkman → ampli/tai nghe. Tính năng thứ hai Sony đã bỏ | Trên Android 9: RRO bật `profile_supported_a2dp_sink`/`avrcp_controller` và tắt source, `pm enable` các service sink, app đi kèm gọi `prepare()` để giữ audio focus. Ba giới hạn: Android 9 không chạy source và sink cùng lúc (mất tai nghe BT khi đang làm loa); chỉ SBC/AAC 16‑bit; Mac chỉ thấy A105 nếu `libbluetooth.so` của Sony được build với `BTA_AV_SINK_INCLUDED=TRUE` (chưa biết). Chi tiết: [docs/a105-wifi-dac.md](docs/a105-wifi-dac.md) mục 7. Trên Linux (H4): BlueZ + PipeWire làm sẵn | Trung bình |
| H3 | **"Walkman One" cho A100** – mod bảng tuning CXD3778GF | Đổi chất âm ở tầng driver, giống các mod nổi tiếng của dòng WM1A/ZX300 (cùng họ codec). Chưa ai làm cho Android Walkman | Đọc `cxd3778gf_table.c`, `cxd3778gf_register.c`; dùng `cxd3778gf_regmon` để xem thanh ghi lúc chạy; thử bảng của ICX1295 (ZX500) trên ICX1293 cho đường SE; build kernel | Trung bình: sai bảng có thể tắt tiếng, không hỏng phần cứng |
| H4 | **Mainline Linux / postmarketOS** | Hệ điều hành của riêng bạn: MPD + librespot + shairport‑sync + snapclient + BlueZ A2DP sink + UAC2 gadget + NFC tap‑to‑play. Mọi thứ Sony bỏ đều có trên Linux | Xem lộ trình 8.4 | Cao về thời gian, thấp về brick (giữ u‑boot Sony, dùng slot B) |
| H5 | **Firmware M4 tự viết** | Low‑Power Audio của riêng bạn (ví dụ decode FLAC trên M4 để A53 ngủ lâu hơn), hoặc M4 làm việc khác khi chạy Linux | SDK MCUXpresso `evkmimx8mm/demo_apps/sai_low_power_audio` là mã nguồn mở của chính cơ chế Sony dùng; cần nạp qua `imx_rproc` (mainline) hoặc u‑boot `bootaux` | Cao: chỉ hợp lý sau H4 |

### 8.3 Cứu khe microSD bằng phần mềm (thử trước khi khò)

Khe thẻ "hỏng" thường là một trong ba trường hợp: (a) cơ cấu push‑push gãy nhưng chân tiếp xúc còn,
(b) công tắc card‑detect gãy/cong, (c) chân data gãy. Chỉ (c) bắt buộc thay socket.

```
# cần root (KernelSU/APatch). GPIO5_IO3 = số 131 trong sysfs/debugfs
adb shell "su -c 'cat /sys/kernel/debug/gpio' | grep -n gpio-131"
# cắm thẻ, giữ tay cho thẻ nằm đúng vị trí, chạy lại lệnh: giá trị phải đổi lo/hi
adb shell "su -c dmesg | grep -iE 'mmc1|usdhc2|sd card'"
```

- Giá trị đổi và `dmesg` thấy `mmc1: new ... SDXC card` → chỉ hỏng cơ cấu giữ thẻ: dán cố định thẻ, xong.
- Giá trị không đổi nhưng thẻ còn tốt → hỏng công tắc detect: sửa DTS. Bung `dtbo_a100.img` bằng
  `dtc`, trong node `usdhc2` thêm `broken-cd;` (kernel sẽ poll thẻ thay vì chờ công tắc) và bỏ
  `svs,icx-cd-gpios`, biên dịch lại, `fastboot flash dtbo_a dtbo.img`. Đồng thời dán cố định thẻ.
- `dmesg` báo CRC/timeout → chân data hỏng, phải thay socket (8.6).

### 8.4 Lộ trình port mainline Linux (postmarketOS) cho ICX1293

1. **An toàn trước**: root, dump toàn bộ phân vùng (`dd` từng `/dev/block/by-name/*` sang PC, đặc biệt
   `nvp`, `boot`, `vbmeta`, `dtbo`, `vendor`). Kiểm tra HAB (secure boot) đã đóng chưa:
   ```
   # bank 1 word 3 bit 25 = SEC_CONFIG[1] trên i.MX 8M Mini
   adb shell "su -c 'dd if=/sys/bus/nvmem/devices/imx-ocotp0/nvmem bs=4 skip=7 count=1 2>/dev/null' | xxd"
   ```
   HAB đóng → **không bao giờ** động vào u‑boot/SPL của Sony; chỉ thay boot image (u‑boot Sony vẫn
   nạp kernel bất kỳ sau `oem unlock`, đã chứng minh bằng kernel KernelSU).
2. **UART**: tìm test‑pad của `uart2` trên PCB (3 chân TX/RX/GND, thử mức 1.8 V trước), lấy log u‑boot.
   Không bắt buộc nhưng rút ngắn mọi bước sau rất nhiều.
3. **Dual‑boot bằng A/B**: giữ Android ở slot A, flash kernel mainline vào `boot_b`,
   `fastboot --set-active=b`. Rootfs đặt trên USB flash qua OTG (chỉ khi bước 3 của mục 8.7 đã xác nhận host mode; H1 chạy ở chế độ gadget nên không chứng minh được) hoặc
   trên `userdata`. Hỏng thì `--set-active=a` quay lại Android.
4. **Bring‑up theo thứ tự**: SoC + PMIC BD71837 + eMMC + UART (có sẵn trong `imx8mm-evk.dts`, copy
   sang `imx8mm-sony-icx1293.dts`) → nút bấm `gpio-keys` → Wi‑Fi QCA9377 SDIO (thử `ath10k_sdio`; firmware
   `qwlan30.bin`/`bdwlan30.bin`/`otp30.bin` lấy từ phân vùng vendor) → BT HCI‑UART → panel HX83102D (thêm chuỗi init lấy từ driver Sony vào
   `panel-himax-hx83102`) → cảm ứng Himax SPI (port driver Sony) → sạc/gauge (port `bq25898-icx`,
   MAX1704x có sẵn) → Type‑C (port `fusb303d` của Sony, mainline chưa có) → **codec CXD3778GF**
   (port ASoC driver Sony, việc lớn nhất) → GPU etnaviv, VPU hantro → NFC `nxp-nci` → M4 `imx_rproc`.
5. **Payload**: Alpine/pmOS + PipeWire + MPD/mpd‑web, librespot (Spotify Connect), shairport‑sync
   (AirPlay), snapclient, BlueZ (A2DP sink), gadget UAC2, neard (NFC). Giao diện: Sxmo, hoặc app
   LVGL/Flutter toàn màn hình cho 3.6".

### 8.5 Lõi M4: Sony dùng để làm gì và bạn làm được gì

Theo DTS, A53 gửi PCM vào vùng RAM dành riêng (`fsl,dma-buffer-size` 96 MB) và M4 bơm ra SAI qua RPMsg
("Low‑Power Audio"). Đây đúng là thiết kế tham chiếu NXP (AN12195 / `sai_low_power_audio`), nên
mã nguồn gốc của cơ chế này là mở. Hướng đi:

- Dump firmware M4 từ gói firmware đã bung (tìm blob không phải ARM64, thường nằm trong `vendor/firmware`
  hoặc một phân vùng riêng mà u‑boot `bootaux`), mở bằng Ghidra để xem Sony có thêm gì (DSD? lệnh riêng?).
- Viết firmware mới từ SDK: thêm decode FLAC/MP3 trên M4 để A53 ngủ cả khi nghe lossless, hoặc
  dùng M4 làm bộ điều khiển nút/LED/BLE khi chạy Linux.
- Chỉ làm trong bối cảnh H4: driver `sony,imx8mm-rpmsg-i2s` của Android gắn chặt với giao thức của Sony.

### 8.6 Hardcore phần cứng

| Việc | Khả thi | Ghi chú |
|---|---|---|
| Thay socket microSD | Có, tiệm khò làm được | Chỉ khi 8.3 kết luận chân data hỏng. Chụp ảnh PCB trước khi tháo |
| Thay eMMC 16 GB → 128/256 GB | Có nguyên tắc, rất khó | Phải dump đủ phân vùng trước (nhất là `nvp` chứa khoá thiết bị và mã vùng); reball BGA‑153; nạp lại bằng `uuu` qua chế độ Serial Download của ROM i.MX (eMMC trống → ROM tự rơi vào SDP). Chỉ nạp lại đúng ảnh u‑boot/SPL đã dump để không vướng HAB |
| Thêm jack 4.4 mm balanced | Không | Codec hỗ trợ BTL nhưng PCB A100 không có tầng ra BTL và nguồn 5 V/7 V cho nó |
| Hàn dây UART | Có, dễ | Bước đầu của mọi việc ở 8.4 |
| Đổi pin lớn hơn | Có | Phải cập nhật bảng model pin MAX1704x trong DTS (`full_battery_capacity`, `model_data`) nếu không gauge báo sai |

### 8.7 Bắt đầu từ đâu nếu chọn hardcore

1. Root + dump phân vùng + kiểm tra HAB (một buổi tối).
2. Chẩn đoán khe thẻ theo 8.3 (một giờ). Có thể giải quyết luôn vấn đề gốc.
3. Thử OTG với flash drive và DAC USB (mười phút) để xác nhận host mode.
4. H1 USB DAC mode (một cuối tuần): kernel + configfs + daemon.
5. H3 mod tuning codec (vài cuối tuần, phụ thuộc đọc hiểu driver).
6. H4 mainline Linux (vài tháng, làm dần theo 8.4). Khi codec chạy được trên mainline, mọi thứ còn lại là phần mềm Linux thông thường.

Nguồn: DTS và config trong [97lily/2019_android_walkman](https://github.com/97lily/2019_android_walkman)
(`kernel_imx/arch/arm64/boot/dts/sony/`, `kernel_imx/walkman.config`, `kernel_imx/sound/soc/codecs/cxd3778gf/`),
[NXP AN12195 Low‑Power Audio on i.MX8M](https://www.nxp.com/docs/en/application-note/AN12195.pdf),
[NXP M4 Low Power Demo trên i.MX8MM](https://community.nxp.com/t5/i-MX-Processors-Knowledge-Base/M4-Low-Power-Demo-on-i-MX8MM/ta-p/1101109),
[panel-himax-hx83102 mainline](https://codebrowser.dev/linux/linux/drivers/gpu/drm/panel/panel-himax-hx83102.c.html),
[bq25890_charger mainline và giới hạn với BQ25898](https://e2e.ti.com/support/power-management-group/power-management/f/power-management-forum/589850/linux-bq25898d-bq25898d),
[FUSB303 datasheet](https://www.onsemi.com/download/data-sheet/pdf/fusb303b-d.pdf).

---

## 9. A105 làm DAC không dây cho Mac (Wi‑Fi / Bluetooth)

Mac → A105 → loa, điều khiển trên A105, màn hình hiện thông số. Thiết kế đầy đủ, lệnh cài đặt và checklist kiểm tra:
**[docs/a105-wifi-dac.md](docs/a105-wifi-dac.md)**.

Tóm tắt:

| Muốn gì | Dùng gì | Đổi lại |
|---|---|---|
| Nghe ngay, miễn phí | AirPlay Receiver của jqssun, Mac chọn trong Control Center › Sound | ALAC 44.1/16, trễ ~2 s. Có thể không kết nối được: UxPlay chỉ hỗ trợ FairPlay loại 3 |
| Nghe ngay, khả năng chạy cao | Airfoil (Mac, trả phí, có bản dùng thử) + Airfoil Satellite (A105) | Trễ ~2 s, ít thông số. Chưa ai thử trên A105; có báo lỗi mất tiếng trên macOS 26 |
| Trễ thấp, miễn phí | roc‑vad + roc‑droid 0.2.2, hoặc SonoBus + BlackHole | Không điều khiển được Mac từ A105. roc chỉ 16‑bit/44.1 kHz; roc‑droid 0.2.2 (libroc 0.2.x) và roc‑vad (roc‑toolkit 0.4) chưa ai xác nhận tương thích |
| **Đúng ý: nút cứng điều khiển Mac, màn hình thông số** (24‑bit chỉ có ích khi bật High‑Res streaming) | **Tự xây WalkDAC**: helper Mac (BlackHole/process tap + mediaremote-adapter) + app A105 (AudioTrack float, bộ đệm 400–500 ms, MediaSession) | Phải viết code. Không cần root |
| Bit‑perfect | WalkDAC + daemon root ghi thẳng ALSA `hires-out` | Cần root |
| Mac gửi qua Bluetooth | A2DP sink sau khi root | SBC/AAC 16‑bit, không ra được loa BT cùng lúc, phụ thuộc cờ biên dịch của Sony |

**Mã nguồn MVP** của bản tự xây nằm trong [`walkdac/`](walkdac/README.md) (sender Python + app Android + test). Chưa thử trên máy thật.

Hai giới hạn của A105 cần nhớ:
- App bên thứ ba không bit‑perfect trên firmware gốc (theo trích đoạn Help Guide của Sony, cần kiểm bằng `dumpsys`). *High‑Res streaming* OFF thì ra 48 kHz/16‑bit, ON thì ra 192 kHz/32‑bit. Nên để OFF và gửi 48 kHz.
- Khoá Wi‑Fi `WIFI_MODE_FULL_HIGH_PERF` không có tác dụng trên QCA9377 + Android 9. App phải giữ wake lock và gửi gói đều.
