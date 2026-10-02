# NW-A105 làm DAC không dây cho Mac (Wi‑Fi / Bluetooth)

Mục tiêu: **Mac → A105 → loa**, điều khiển được ngay trên A105 (nút cứng + màn hình cảm ứng),
và màn hình A105 hiển thị thông số kỹ thuật theo thời gian thực.

Tài liệu này dựa trên một vòng nghiên cứu có phản biện chéo. 37 trong 77 kết luận then chốt được hai agent độc lập
kiểm tra lại, chủ yếu bằng mã nguồn (kernel GPL của Sony, AOSP/LineageOS 16 = Android 9, UxPlay, Snapcast,
roc‑toolkit, BlackHole, mediaremote-adapter) và tài liệu chính thức của Apple/Android. Sáu câu hỏi mở được nghiên cứu thêm
bằng cách đọc mã nguồn. Phần còn lại, gồm vài điểm then chốt (thông số BlackHole, tham số mặc định của Snapcast/roc,
các bước bật A2DP sink), chỉ dựa trên nguồn mà người nghiên cứu ban đầu đã đọc.
Những chỗ ghi **(chưa kiểm chứng)** là suy luận hoặc chỉ dựa trên trích đoạn tìm kiếm; mục 10 có lệnh để bạn tự kiểm tra trên máy.

---

## 0. Kết luận nhanh

- **Wi‑Fi: làm được, và là đường nên đi.** Không cần root cho mọi thứ trừ chế độ bit‑perfect.
- **Không có giải pháp dựng sẵn nào đáp ứng đủ cả ba yêu cầu** (âm thanh hệ thống của Mac + điều khiển từ A105 +
  màn hình thông số chi tiết). Muốn đủ cả ba thì phải tự viết hai phần nhỏ: một helper trên Mac và một app trên A105
  (ở đây gọi tạm là **WalkDAC**, mục 4). Mọi API cần thiết đều có trên Android 9.
- **Muốn nghe ngay hôm nay**: thử app AirPlay miễn phí (10 phút, có thể không chạy với Mac, xem 3.1).
  Nếu không được thì dùng Airfoil + Airfoil Satellite (trả phí) hoặc roc‑vad + roc‑droid (miễn phí, trễ thấp, không điều khiển).
- **Bluetooth từ Mac vào A105**: chỉ khả thi sau khi root, chất lượng thấp hơn (SBC/AAC 16‑bit), rủi ro phụ thuộc một cờ
  biên dịch của Sony, và khi đó A105 **không thể** phát tiếp ra loa Bluetooth. Chỉ nên coi là thí nghiệm (mục 7).
- **Ra loa nào**: nên dùng loa active hoặc ampli cắm jack 3.5 mm. Đây là đường duy nhất mà *High‑Res streaming* có tác dụng,
  giữ đủ hiệu ứng Sony, không cộng thêm 0.2–0.35 s trễ và không tranh sóng với Wi‑Fi.
  Loa Bluetooth vẫn dùng được (LDAC/aptX HD, mục 6) nhưng là lựa chọn thứ hai: Wi‑Fi phải ở 5 GHz và âm thanh bị nén thêm một lần.
- **Giới hạn cứng của A105** (theo trích đoạn Help Guide của Sony và một bản dumpsys trên ZX507, chưa đo trên A105 4.06):
  âm thanh từ app bên thứ ba **không bit‑perfect** trên firmware gốc. Công tắc *High‑Res streaming* tắt thì bị hạ về 48 kHz/16‑bit,
  bật thì bị nâng lên 192 kHz/32‑bit. Sony cũng ghi chức năng này không hỗ trợ mọi app stream (mục 5; kiểm bằng `dumpsys`, mục 10).
  Bit‑perfect chỉ có khi root và ghi thẳng vào ALSA (mục 8).
- **High‑Res streaming nên để OFF** và gửi 48 kHz. Chưa có bằng chứng 24‑bit/96 kHz qua đường này nghe khác 16/44.1,
  và OFF đỡ tốn khoảng 20% pin, đỡ nóng khi cắm sạc 24/7. Chỉ bật ON (rồi khởi động lại) khi muốn thử hi‑res.
- **Đính chính README cũ**: chip Wi‑Fi/BT của A105 là **Qualcomm QCA9377** (driver `qcacld-2.0_sony` trong kernel GPL;
  tên module Murata Type 1PJ theo trích đoạn), không phải Broadcom.
  Riêng khoá Wi‑Fi `WIFI_MODE_FULL_HIGH_PERF` thì **không có tác dụng**, vì hai lý do độc lập: Android 9 chỉ dùng khoá này để thống kê pin,
  và lệnh `SETSUSPENDMODE` trong driver của Sony là khối rỗng (mục 9). Đo RTT có và không có khoá (mục 10) để chắc chắn.

---

## 1. Chuỗi tín hiệu và những chỗ bị giới hạn

```
 MAC                                   MẠNG                 A105 (Android 9)                                   LOA
 ┌───────────────────────────┐                             ┌──────────────────────────────────────────────┐
 │ App (Spotify/Music/Web…)  │                             │ App nhận ──► AudioFlinger (mixer float)        │
 │   │ Core Audio mixer       │   Wi‑Fi 5 GHz (TCP/UDP)    │              │  High‑Res streaming:            │
 │   ▼ (resample về rate     ├────────────────────────────►│              │  OFF → 48 kHz/16‑bit            │
 │   thiết bị ra)            │                             │              │  ON  → 192 kHz/32‑bit           │
 │ BlackHole / Process tap   │                             │              ▼                                 │
 │   ▼                       │                             │ Sony audio HAL (EQ, DSEE HX, ClearAudio+, …,   │
 │ Helper: mã hoá + gửi      │◄────── lệnh điều khiển ─────┤   Direct Source = bỏ qua hết)                  │
 │ + đọc "Now Playing"       │        + thống kê           │              ▼                                 │
 └───────────────────────────┘                             │ CXD3778GF (S‑Master HX) ─► jack 3.5 mm ────────┼──► loa active / ampli
                                                           │        hoặc A2DP (LDAC/aptX HD/AAC/SBC) ───────┼──► loa Bluetooth
                                                           └──────────────────────────────────────────────┘
```

| Chỗ thắt | Giới hạn | Hệ quả |
|---|---|---|
| Core Audio trên Mac | Mọi app bị resample về sample rate danh định của thiết bị ra (Audio MIDI Setup). App Music không tự đổi rate | Muốn giữ rate gốc phải đổi rate thiết bị (LosslessSwitcher, hoặc helper tự đổi) |
| ScreenCaptureKit | Chỉ 8/16/24/48 kHz, 1–2 kênh | Không dùng để bắt âm thanh hi‑res |
| AirPlay từ Control Center | Luồng *Realtime*: ALAC 44.1 kHz/16‑bit, trễ ~2 s | Trần chất lượng = CD |
| Android 9 Java AudioTrack | PCM 8/16‑bit hoặc float; tối đa 192 kHz | 24‑bit phải chuyển sang float (không mất gì) |
| Sony *High‑Res streaming* | OFF: 48/16, ON: 192/32, cần khởi động lại sau khi đổi | Không bit‑perfect nếu không root |
| Sony HAL | Hiệu ứng Sony nằm trong HAL nên áp lên app bên thứ ba nếu đang bật | Muốn "sạch" thì bật *Direct Source* |
| Wi‑Fi QCA9377 | Power save không tắt được bằng API (không root) | Cần bộ đệm đủ lớn, luồng gói đều |

---

## 2. So sánh các phương án

| Phương án | Mac cần | A105 cần | Định dạng qua mạng | Độ trễ | Điều khiển từ A105 | Thông số trên màn A105 | Root |
|---|---|---|---|---|---|---|---|
| **A. AirPlay miễn phí** (jqssun AirPlay Receiver) | Không cài gì | App `io.github.jqssun.airplay` | ALAC 44.1/16 | ~2 s | Có code DACP, **chưa ai xác nhận** chạy với âm thanh hệ thống macOS | Codec, bộ đệm, số lần giật (overlay debug) | Không |
| **B. Airfoil + Airfoil Satellite** (trả phí) | Airfoil 5.13 (macOS 14.4+) | Airfoil Satellite (Android 6+) | Chưa rõ (kiểu AirPlay) | ~2 s | Có cho một số nguồn (Spotify, Apple Music) | Metadata | Không |
| **C. roc‑vad + roc‑droid** | roc‑vad (thiết bị ảo) | roc‑droid **0.2.2** | L16 44.1/16 + FEC | ~200 ms | Không | Không | Không |
| **D. SonoBus + BlackHole** | SonoBus + BlackHole | SonoBus (Android 7+) | PCM 16/24/32 hoặc Opus | Thấp (chỉnh được) | Không | Độ trễ, jitter mỗi peer | Không |
| **E. Snapcast + BlackHole** | snapserver + sox | Snapdroid | FLAC/PCM tới 96 kHz/24 (xem lưu ý) | 1 s mặc định | Âm lượng/nhóm, không điều khiển app Mac | Ít | Không |
| **F. WalkDAC (tự viết)** | Helper (Swift) | App (Kotlin + NDK) | FLAC/PCM ≤192 kHz/24 | 0.4–0.5 s mặc định, 0.2 s nếu mạng sạch | **Đầy đủ** (nút cứng, cảm ứng, metadata) | **Đầy đủ** (mục 4.6) | Không |
| **G. Bluetooth A2DP sink** | Không cài gì | Mod hệ thống + app đi kèm | SBC/AAC 16‑bit 44.1/48 | ~100–220 ms | Có thể (AVRCP, chưa kiểm chứng với macOS) | Chỉ sample rate/kênh (codec cần root) | **Có** |
| **H. DAC bit‑perfect** (thêm vào F) | Như F | Daemon root ghi thẳng ALSA | Như F | Như F | Như F | Như F + định dạng ALSA thật | **Có** |

Ghi chú độ trễ: nếu A105 phát tiếp ra loa Bluetooth, cộng thêm khoảng **0.2–0.35 s**.
Với video, chỉ AirPlay được macOS tự bù tiếng/hình (Music, Chrome, Firefox bù; IINA thì không).
Với các đường dùng thiết bị ảo (BlackHole, roc‑vad), macOS không biết độ trễ mạng nên hình sẽ đi trước tiếng
bằng đúng độ trễ bộ đệm (xem 4.2 về `kLatency_Frame_Size` của BlackHole).

---

## 3. Làm ngay, không viết code

> **Trước khi dán lệnh vào Terminal của Mac:** Terminal mặc định dùng zsh, và zsh **không** coi `#` là chú thích khi dán lệnh
> (trừ khi đã bật, ví dụ qua Oh My Zsh). Chú thích cuối dòng sẽ thành đối số và làm hỏng lệnh. Chạy một lần
> `setopt interactivecomments` (hoặc thêm dòng đó vào `~/.zshrc`).

### 3.1 Thử AirPlay miễn phí (10 phút, có thể thất bại)

App: **AirPlay Receiver & Server** của jqssun (`io.github.jqssun.airplay`, GPLv3, dựa trên UxPlay),
bản 0.0.31 (2026‑08‑23), minSdk 24 nên cài được trên Android 9. Có trên F‑Droid, GitHub Releases (APK universal) và Play.

**Rủi ro lớn nhất (chưa kiểm chứng trên Mac của bạn):** UxPlay chỉ hỗ trợ FairPlay **loại 3**.
App Music trên macOS dùng FairPlay **loại 2** nên không phát được (UxPlay issue #570, đóng với lý do "không sửa được").
Chưa có nguồn nào cho biết Control Center › Sound dùng loại nào cho âm thanh hệ thống. Nếu cũng là loại 2, macOS sẽ báo
"Could not connect". Đường còn lại khi đó là *Screen Mirroring*, macOS sẽ chuyển âm thanh sang A105 nhưng dưới dạng AAC (có mất mát)
kèm một luồng video A105 phải giải mã.

Các bước:

1. Cài APK, mở app, vào *Developer options* của app, bật **Advertise ALAC codec** (mặc định tắt), giữ *Advertise AirPlay audio support* bật.
2. Trong Settings của A105, gỡ hạn chế pin cho app (không để *Background restriction*).
3. Mac và A105 cùng mạng, ưu tiên 5 GHz. Trên Mac mở Control Center › Sound, chọn tên A105, phát nhạc.
4. Nếu không kết nối được, đọc byte loại FairPlay để biết lý do. `en0` là Wi‑Fi trên MacBook; Mac để bàn dùng Wi‑Fi thì thường là `en1`
   (xem bằng `networksetup -listallhardwareports`):
   ```
   sudo tcpdump -i en0 -s0 -X 'tcp and host <IP_A105> and port 7000'
   # tìm thân POST /fp-setup: bắt đầu bằng 46 50 4c 59 ("FPLY"), byte kế tiếp là loại FairPlay
   # 0x03 = được hỗ trợ, 0x02 = sẽ thất bại
   ```
5. Nếu phát được, thử điều khiển ngược: bấm next/pause trên A105. Điều khiển chỉ chạy khi Mac gửi kèm header
   `DACP-ID` và `Active-Remote` trong lệnh SETUP của luồng âm thanh. jqssun 0.0.31 bỏ qua các header nằm trong `GET /info`,
   và tìm dịch vụ theo đúng chuỗi `iTunes_Ctrl_<DACP-ID>`, nên DACP-ID bắt đầu bằng số 0 có thể làm bước tìm thất bại
   (log `DACP resolve failed`). Không thấy dòng `DACP resolved` trong log thì mọi lệnh đều hỏng mà không báo. Lệnh kiểm tra đầy đủ ở mục 10.

Giới hạn đã biết: ALAC 44.1/16, trễ ~2 s; overlay debug chỉ có codec, bộ đệm (ms) so với mục tiêu, bộ đếm giật
(trim/drop/silence/underrun/xrun) và thời gian giải mã; không có sample rate, RSSI, pin, nhiệt độ.
Một người dùng báo âm lượng bị đẩy lên tối đa mỗi lần kết nối (issue #36).
App không giữ khoá Wi‑Fi và chưa ai thử trên Android 9 hay trên máy Sony.

### 3.2 Airfoil + Airfoil Satellite (trả phí, khả năng chạy cao, chưa ai thử trên A105)

- Mac: Airfoil 5.13 (cần macOS 14.4+). Bắt âm thanh của bất kỳ app nào hoặc toàn hệ thống.
- A105: Airfoil Satellite (miễn phí, Android 6+). Vừa là đầu nhận vừa là remote: play/pause/skip cho một số nguồn
  như Spotify và Apple Music, hiện metadata.
- Trễ kiểu AirPlay (~2 s), có thiết lập bù tiếng/hình. Định dạng gửi tới Android chưa xác định được.
- Bản 5.12.6 có ghi nhận hai lỗi mất tiếng trên macOS 26 (Tahoe). Dùng bản dùng thử trước khi mua.
- Không hiện thông số kỹ thuật chi tiết.
- Phần lớn thông tin chỉ dựa trên trích đoạn tìm kiếm vì trang của Rogue Amoeba bị chặn khi nghiên cứu.

### 3.3 Độ trễ thấp, miễn phí: roc‑vad + roc‑droid (hoặc SonoBus)

**roc** (RTP/UDP + sửa lỗi Reed‑Solomon, mặc định trễ 200 ms):

```
# Mac (macOS 10.15+, Intel và Apple Silicon); khởi động lại máy sau khi cài
sudo /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/roc-streaming/roc-vad/HEAD/install.sh)"
roc-vad device add sender --name "A105"
roc-vad device list
roc-vad device connect 1 \
   --source rtp+rs8m://<IP_A105>:10001 \
   --repair rs8m://<IP_A105>:10002
# chọn thiết bị "A105" làm Sound Output
```

- A105: cài **roc‑droid 0.2.2** (F‑Droid/IzzyOnDroid/GitHub, minSdk 26) và **giữ nguyên bản này**:
  bản viết lại bằng Flutter (0.3.x) đòi Android 10.
- roc‑droid cố định 44.1 kHz stereo, cổng 10001/10002; roc chỉ mang PCM 16‑bit.
- **Chưa kiểm chứng**: roc‑droid 0.2.2 dùng libroc 0.2.x còn roc‑vad dùng roc‑toolkit 0.4; tương thích giao thức giữa hai bản chưa ai xác nhận.
- Không có metadata, không điều khiển ngược.

**SonoBus** (miễn phí, Mac + Android 7+): cài BlackHole 2ch trên Mac, đặt BlackHole làm Sound Output, trong SonoBus chọn
BlackHole làm input, gửi PCM 24‑bit sang SonoBus trên A105. SonoBus hiện độ trễ một chiều, RTT và trạng thái jitter buffer
của từng peer. Không điều khiển được app trên Mac.

### 3.4 Hi‑res kiểu DIY: Snapcast + BlackHole

```
# Mac
brew install blackhole-2ch snapcast sox
# Audio MIDI Setup: đặt BlackHole 2ch = 48000 Hz; System Settings › Sound › Output = BlackHole 2ch
mkdir -p ~/bin
cat > ~/bin/mac-capture.sh <<'EOF'
#!/bin/sh
exec /opt/homebrew/bin/sox -q -t coreaudio "BlackHole 2ch" -t raw -r 48000 -b 16 -e signed-integer -c 2 -
EOF
chmod +x ~/bin/mac-capture.sh
```

Mac Intel: thay `/opt/homebrew` bằng `/usr/local` (trong script và trong đường dẫn cấu hình); kiểm tra bằng `brew --prefix`.

Trong `/opt/homebrew/etc/snapserver.conf`, mục `[stream]`, **thay** dòng đang bật sẵn `source = pipe:///tmp/snapfifo?name=default`
bằng hai dòng dưới. Nếu giữ dòng cũ, Snapdroid sẽ vào luồng `default` im lặng:

```
source = process:///Users/<ban>/bin/mac-capture.sh?name=Mac&sampleformat=48000:16:2&codec=flac
buffer = 500
```

Chạy `snapserver -c /opt/homebrew/etc/snapserver.conf` **từ Terminal** (thiếu `-c` thì snapserver đọc `/etc/snapserver.conf`
và chạy cấu hình mặc định). Cài **Snapdroid** trên A105 (minSdk 21). Snapdroid tự tìm server qua mDNS; nếu không thấy thì nhập IP của Mac.

Lưu ý:
- Đọc BlackHole bằng `sox -t coreaudio` là cách được mô tả trong nghiên cứu nhưng chưa được phản biện, cần chạy thử.
- Lần đầu chạy, macOS hỏi quyền *Microphone* cho Terminal, vì sox đọc BlackHole như một đầu vào. Phải cho phép.
  Nếu chạy qua `brew services`, ssh hoặc launchd, hoặc từ chối quyền, sox chỉ đọc ra im lặng mà không báo lỗi.
- Bản macOS của Snapcast vẫn được dự án ghi là *experimental*.
- **Đừng dùng 24‑bit với Snapdroid trên A105**: OboePlayer của snapclient xin định dạng `I24`, mà định dạng này chỉ có từ Android 12 (API 31).
  Trên Android 9 hãy dùng 16‑bit, hoặc vá OboePlayer sang `Float`.
- `buffer` mặc định 1000 ms; 500 ms là mức khởi đầu hợp lý trên 5 GHz.
- Các tham số `params=` trong URI phải mã hoá URL (`%20` thay dấu cách), nên dùng script bọc như trên cho gọn.

---

## 4. WalkDAC: tự xây đúng ý bạn

### 4.1 Kiến trúc

```
MAC  ── WalkDAC Helper (Swift, menu bar) ─────────────────────────────────────────────
  [Capture]  BlackHole 2ch (macOS 10.10+)  hoặc  Core Audio process tap (macOS 14.2+)
      │ float32 @ rate danh định của thiết bị
  [Encoder]  FLAC (libFLAC, level 0–2) hoặc PCM s16/s24
      │ khối 10–20 ms, đóng dấu thời gian lúc bắt âm
  [Audio TCP :7700] ───────────────────────────────────────────────►  A105
  [Control WebSocket :7701]  ◄── lệnh / thống kê ──►  
  [Now Playing]  mediaremote-adapter "stream" (qua /usr/bin/perl)  → metadata, ảnh bìa
  [Commands]     mediaremote-adapter "send"/"seek" → app đang được macOS chọn là Now Playing
  [Discovery]    Bonjour _walkdac._tcp

A105 ── WalkDAC Receiver (Kotlin, minSdk 28, + NDK cho libFLAC/speexdsp) ───────────
  ForegroundService + PARTIAL_WAKE_LOCK
  NetThread ─► JitterBuffer (mục tiêu 400–500 ms) ─► Decoder ─► DriftController (PI, ±500 ppm)
           ─► Resampler (speexdsp) ─► AudioTrack ENCODING_PCM_FLOAT ─► Sony HAL ─► jack / LDAC
                                 └─► Meter/FFT (VU, phổ)
  MediaSessionCompat  ◄── nút cứng, tai nghe, màn khoá ──►  ControlClient (WebSocket)
  StatsCollector (1 Hz) ─► StatsActivity (màn hình thông số)
```

### 4.2 Phía Mac

**Bắt âm thanh** (chọn một):

| Cách | Ưu | Nhược |
|---|---|---|
| BlackHole 2ch làm Sound Output duy nhất | Đơn giản, chạy từ macOS 10.10, 8 kHz–768 kHz, float32, không thêm trễ | Phải cài driver. Phím âm lượng Mac giảm tín hiệu **bằng số** (chỉ bit‑exact ở 100%). Loa Mac câm. App đọc BlackHole cần quyền **Microphone**; bị từ chối thì macOS trả toàn mẫu 0, A105 chỉ nhận im lặng |
| Core Audio process tap (`AudioHardwareCreateProcessTap` + `CATapDescription`) | Không cần driver, chọn được app cần bắt | Cần macOS 14.2+, khai báo `NSAudioCaptureUsageDescription`, hộp thoại xin quyền lần đầu. Tap "stereo mixdown" luôn báo 48 kHz dù thiết bị chạy rate khác |

Quy tắc bắt buộc:
- Luôn đọc rate thật từ thiết bị (`kAudioDevicePropertyNominalSampleRate`), không tin định dạng mà tap báo.
  Đăng ký `AudioObjectAddPropertyListener` cho thuộc tính này, vì rate đổi giữa chừng khi dùng LosslessSwitcher hoặc chỉnh Audio MIDI Setup.
  Khi rate đổi, helper gửi lại `{"t":"source",…}` và bắt đầu khối mới; A105 thấy `sample_rate` trong header đổi thì xả bộ đệm và tạo lại AudioTrack.
- Đừng dùng Multi‑Output Device làm đầu ra chính vì nó không có master volume.
- Giữ âm lượng Mac ở 100% và **chỉnh âm lượng trên A105**. Như vậy chỉ có một tầng giảm âm, nằm ở phía S‑Master HX.
  Helper nên cảnh báo khi âm lượng BlackHole khác 100%.
- Chọn rate theo đường ra: jack + High‑Res streaming OFF → 48 kHz; jack + ON → 48/96/192 kHz;
  loa Bluetooth → 48 kHz và ép 48 kHz trong Developer options (mục 6).
- **Quyền trên macOS** (gặp ngay ở M1):
  1. Microphone khi đọc BlackHole (`NSMicrophoneUsageDescription`; script chạy từ Terminal thì Terminal phải được cấp).
  2. Process tap: `NSAudioCaptureUsageDescription`.
  3. Từ macOS 15, app dùng Bonjour hoặc kết nối tới thiết bị trong LAN cần quyền **Local Network**
     (`NSLocalNetworkUsageDescription`, `NSBonjourServices` = `_walkdac._tcp`). Bật lại ở System Settings › Privacy & Security › Local Network.
  4. Nếu bật tường lửa macOS, helper nghe cổng 7700/7701 sẽ bị hỏi có cho nhận kết nối đến không.
- Lip‑sync video: BlackHole báo độ trễ 0 cho macOS. Có thể build BlackHole với hằng số `kLatency_Frame_Size` bằng độ trễ bộ đệm
  để các app video tự bù **(ý tưởng, chưa thử)**.

**Now Playing và điều khiển:**
- Từ macOS 15.4, daemon `mediaremoted` không trả thông tin Now Playing cho app bên thứ ba thông thường.
- Cách đang chạy được tới macOS 27 (thử ngày 2026‑09‑04) là **mediaremote-adapter**: nạp framework riêng bên trong `/usr/bin/perl` (một binary ký bởi Apple).
  ```
  brew install media-control
  media-control get -h
  media-control stream
  media-control toggle-play-pause
  P=$(brew --prefix media-control)
  A="$P/lib/media-control/mediaremote-adapter.pl"
  F="$P/Frameworks/MediaRemoteAdapter.framework"
  /usr/bin/perl "$A" "$F" send 4
  /usr/bin/perl "$A" "$F" seek 90000000
  /usr/bin/perl "$A" "$F" "$P/lib/media-control/MediaRemoteAdapterTestClient" test; echo $?
  ```
  - `send 4` là next; `seek` tính bằng micro giây (90000000 = 90 s); `test` trả 0 nghĩa là adapter còn chạy. Đường dẫn phải tuyệt đối.
  - `stream` in mỗi dòng dạng `{"type":"data","diff":…,"payload":{…}}`. Từ dòng thứ hai chỉ có các khoá thay đổi (thêm `--no-diff` để luôn đủ).
    Chỉ `bundleIdentifier`, `playing`, `title` chắc chắn có; `artist`, `album`, `duration`, `elapsedTime`, `artworkData` (base64) có thể thiếu.
  - Helper nên chạy một tiến trình sống lâu `/usr/bin/perl "$A" "$F" stream --micros --debounce=100`, gộp các payload diff vào trạng thái hiện tại
    rồi mới gửi `now_playing`. A105 tự tính vị trí = elapsedTime + playbackRate × (now − timestamp), không cần Mac gửi mỗi giây.
- Mã lệnh `send`: 0 play, 1 pause, 2 toggle, 3 stop, 4 next, 5 previous. **Không có lệnh âm lượng.**
- Lệnh luôn đến app mà macOS đang chọn làm Now Playing (app bắt đầu phát gần nhất), không chọn được app đích. Ngoại lệ là app Music của Apple.
- Lúc khởi động, chạy lệnh `test` của adapter. Nếu Apple chặn cách này ở bản macOS sau, chuyển sang AppleScript cho Music/Spotify.
  Music trên macOS 26.0–26.2 lỗi đọc `current track` với bài stream; 26.3 sửa phần tên bài nhưng chưa đọc được ảnh bìa bài stream.
- **Không dùng phím media giả lập**: cần thêm quyền, vẫn đi tới đúng app như trên, và có báo cáo bị chặn với daemon chưa ký trên macOS 26.5.

### 4.3 Giao thức đề xuất

**Luồng âm thanh**: TCP, một kết nối, mỗi khối 10–20 ms, bật `TCP_NODELAY` ở phía gửi để Nagle không giữ đuôi khối chờ ACK. Header nhị phân little‑endian 28 byte:

| Offset | Kích thước | Trường |
|---|---|---|
| 0 | 2 | magic `"WD"` |
| 2 | 1 | version = 1 |
| 3 | 1 | codec: 0 pcm_s16, 1 pcm_s24, 2 pcm_f32, 3 flac |
| 4 | 4 | seq (tăng dần) |
| 8 | 8 | capture_ns (đồng hồ Mac, lúc bắt âm) |
| 16 | 4 | sample_rate |
| 20 | 2 | frames trong khối |
| 22 | 1 | bits |
| 23 | 1 | channels |
| 24 | 4 | payload_len |
| 28 | … | payload |

**Kênh điều khiển**: JSON lines (UTF‑8, một thông điệp mỗi dòng) qua TCP 7701. Đây là giao thức bản MVP trong `walkdac/` đang dùng.

```jsonc
// A105 → Mac
{"t":"hello","name":"NW-A105 WalkDAC","ver":1}
{"t":"time","id":17,"a105_ns":987654321}             // 50 lần cách 100 ms khi kết nối, sau đó 1 lần/s
{"t":"cmd","op":"toggle"}                            // play | pause | toggle | next | prev | seek (pos_us)
{"t":"stats","buffer_ms":482,"target_ms":450,"underruns":0,"lost":0,"drift_ppm":41,"jitter_ms":1.2,"rssi":-52}
// Mac → A105
{"t":"source","name":"MacBook","device":"BlackHole 2ch","capture":"device","rate":48000,"codec":"pcm_s24",
 "bits":24,"channels":2,"block_ms":10,"ver":1,"media_control":true}
{"t":"now_playing","app":"com.spotify.client","playing":true,"title":"…","artist":"…","album":"…",
 "duration_us":245000000,"elapsed_us":83000000,"rate":1.0,"at_mac_ns":123456789,
 "artwork_b64":"…","artwork_mime":"image/jpeg"}      // at_mac_ns: đồng hồ monotonic của Mac, cùng đồng hồ với capture_ns
{"t":"time_ack","id":17,"a105_ns":987654321,"mac_ns":123456789}
{"t":"cmd_ack","op":"toggle","ok":true,"error":""}
{"t":"flush","from_seq":1234}                        // bỏ mọi khối có seq < from_seq (mod 2^32)
// Beacon: UDP broadcast cổng 7702 mỗi 2 s
{"t":"beacon","name":"MacBook","audio_port":7700,"control_port":7701,"ver":1,"rate":48000,"codec":"pcm_s24"}
```

`artwork_b64` chỉ có khi ảnh bìa đổi (giá trị `null` nghĩa là bài hiện tại không có ảnh). Bản MVP gửi PCM, chưa dùng FLAC.
Âm lượng do A105 tự xử lý (4.5 a), nên chưa có lệnh `volume`.

A105 giữ 0.4–0.5 s âm thanh trong bộ đệm, nên nếu chỉ gửi lệnh rồi chờ thì bấm pause/next vẫn nghe nửa giây bài cũ.
Vì vậy khi bấm pause/next/prev/seek, app A105 tự giảm âm về 0 trong ~20 ms và xả bộ đệm ngay, rồi mới gửi `cmd`.
Helper gửi lệnh qua adapter; khi thấy Now Playing đổi (bài mới, `playing:false` hoặc `elapsedTime` mới) thì gửi `flush` với seq kế tiếp,
để A105 không phát lại đuôi bài cũ đã bắt trước khi app trên Mac kịp đổi. Nếu sau ~1 s Mac vẫn báo `playing:true` (app không nhận lệnh),
A105 giữ trạng thái tạm dừng cục bộ và hiện thông báo.

**Các con số** (lấy từ Snapcast và roc):

| Tham số | Giá trị đề xuất | Căn cứ |
|---|---|---|
| Bộ đệm mục tiêu | 400–500 ms, cho chỉnh 200–2000 ms | roc dùng 200 ms với UDP+FEC; Snapcast mặc định 1000 ms qua TCP; power save Wi‑Fi có thể thêm 100–300+ ms |
| Định dạng mặc định | FLAC 48 kHz/24‑bit, level 0–2, đặt blocksize đúng bằng một khối mạng (`FLAC__stream_encoder_set_blocksize`: 480 hoặc 960 frame ở 48 kHz = 10/20 ms; 960/1920 ở 96 kHz). Mặc định 1152 frame = 24 ms ở 48 kHz, vượt mốc ≤ 20 ms ở 4.4 và mục 9 | ≤ 2.3 Mb/s; hi‑res chỉ có ý nghĩa khi bật High‑Res streaming |
| Băng thông dự trù | Theo PCM, vì FLAC có thể gần 100% | 44.1/16 = 1.41 Mb/s · 48/24 = 2.30 · 96/24 = 4.61 · 192/24 = 9.22 Mb/s |
| Bù trôi đồng hồ | Bộ điều khiển PI trên mức đầy bộ đệm, kẹp ±500 ppm, bắt đầu khi lệch ~100 µs | Trôi thực tế giữa hai thiết bị ~55 ppm (roc đo); Snapcast cũng kẹp ±500 ppm |
| Đồng bộ lại cứng | Khi lệch > 50 ms | Ngưỡng của Snapcast |
| Đo độ trễ đầu‑cuối | Độ lệch đồng hồ = trung vị của 200 mẫu (độ trễ chiều đi − độ trễ chiều về)/2, giả định đường hai chiều đối xứng; 50 mẫu nhanh khi kết nối, sau đó 1 mẫu/s. Độ trễ = thời điểm phát (quy về đồng hồ Mac) − `capture_ns` | Cách của Snapcast |

Với một đầu nhận duy nhất thì không cần đồng bộ đồng hồ kiểu NTP để phát. Điều khiển theo mức bộ đệm là đủ.
Dấu thời gian chỉ dùng để **hiển thị** độ trễ đầu‑cuối.

### 4.4 Phía A105

- **Vòng đời**:
  - Chạy dưới dạng *foreground service* (khai báo quyền `FOREGROUND_SERVICE`; thuộc tính `foregroundServiceType` bị bỏ qua trên API 28) và giữ `PARTIAL_WAKE_LOCK`.
  - Trên Android 9, Doze không cắt mạng hay wake lock của foreground service.
  - Theo mã AOSP 9, chỉ *Background restriction* (người dùng đặt) mới dừng được foreground service. Thêm app vào whitelist Doze
    (`adb shell dumpsys deviceidle whitelist +<pkg>`) cho chắc. Firmware Sony có thêm cơ chế tắt app hay không thì chưa kiểm chứng (thử ở mục 10).
- **Wi‑Fi**: khoá `WIFI_MODE_FULL_HIGH_PERF` không có tác dụng trên máy này (mục 9).
  Cách giữ sóng thức là cho gói đến đều: khối ≤ 20 ms, unicast, không multicast.
- **Giải mã**: libFLAC qua NDK (giấy phép BSD).
  Bộ giải mã FLAC của Android chỉ được đảm bảo tới 48 kHz, nên đừng dựa vào nó cho hi‑res. PCM thô cũng được vì 5 GHz thừa băng thông.
- **Phát**:
  - Một `AudioTrack` với `USAGE_MEDIA`, `CONTENT_TYPE_MUSIC`, `ENCODING_PCM_FLOAT`, đúng rate của luồng (≤ 192 kHz).
  - Ghi chế độ `WRITE_BLOCKING` từ một luồng riêng có ưu tiên `THREAD_PRIORITY_URGENT_AUDIO`.
  - **Không** dùng `PERFORMANCE_MODE_LOW_LATENCY`: chế độ này tắt hiệu ứng và đòi rate trùng với mixer.
  - Trên API 28 không có `ENCODING_PCM_24BIT_PACKED`/`32BIT` (có từ API 31). Float giữ trọn 24‑bit.
    OpenSL ES gốc của Android 9 nhận PCM nguyên 24/32‑bit, nhưng chưa thử trên firmware Sony.
- **Bù trôi**: speexdsp resampler (`set_rate_frac`, chất lượng 4–5), hoặc thêm/bớt từng frame như Snapcast.
- **Mất kết nối, Mac ngủ, nhiều Mac** (làm ngay từ M2):
  1. A105 coi là mất nguồn khi không nhận được khối nào trong ~1 s: giảm dần về im lặng (không để underrun gây click), hiện "Chờ Mac…",
     thử kết nối lại theo chu kỳ qua Bonjour, có ô nhập IP tay làm dự phòng.
  2. Không `release()` MediaSession khi mất kết nối, chỉ chuyển sang `STATE_PAUSED`, để phiên vẫn giữ phím media.
  3. Phía Mac: nghe `NSWorkspace.willSleepNotification`/`didWakeNotification` để đóng rồi mở lại kết nối; tắt App Nap cho helper.
     Helper đọc BlackHole liên tục có thể làm Mac không tự ngủ (kiểm tra bằng `pmset -g assertions`); muốn Mac ngủ được thì dừng bắt âm sau vài phút im lặng.
  4. Nhiều Mac: A105 chỉ nhận một nguồn một lúc; nguồn thứ hai bị từ chối hoặc người dùng chọn trên màn A105.
  5. Tự chạy lại service sau khi bật máy (`RECEIVE_BOOT_COMPLETED`), vì mỗi lần đổi High‑Res streaming phải khởi động lại.
- **Đo đạc**: lấy độ trễ ra từ `getTimestamp()`, số lần thiếu dữ liệu từ `getUnderrunCount()`, kích thước bộ đệm từ `getBufferSizeInFrames()`. Tính VU và phổ bằng FFT ngay trên PCM của app,
  không dùng `Visualizer` vì nó cần quyền ghi âm.

### 4.5 Nút cứng và âm lượng

- Theo kernel của Sony, năm nút là `gpio-keys` chuẩn:
  KEY_PLAYPAUSE (164), KEY_VOLUMEUP (115), KEY_VOLUMEDOWN (114), KEY_NEXTSONG (163), KEY_PREVIOUSSONG (165).
  Với keylayout chuẩn, chúng thành MEDIA_PLAY_PAUSE / MEDIA_NEXT / MEDIA_PREVIOUS / VOLUME_UP / VOLUME_DOWN.
- Từ Android 8, phím media đến MediaSession của **app phát âm thanh gần nhất**. Vì vậy app nhận phải tự phát qua AudioTrack,
  cùng UID với MediaSession. Mã AOSP 9 không bắt buộc `STATE_PLAYING` hay `setActive(true)`, nhưng vẫn nên đặt cả hai
  (thông báo, màn khoá) và gắn metadata từ Mac. Android 9 gốc vẫn chuyển phím media khi màn tắt mà không bật màn.
- App mất phím khi app khác (kể cả Music player của Sony) phát sau nó, khi cửa sổ đang focus tự xử lý `KEYCODE_MEDIA_*`
  (StatsActivity đừng bắt các phím này), khi có session global priority, hoặc khi một app hệ thống giữ `OnMediaKeyListener`.
  Firmware Sony có làm vậy không thì chưa biết; kiểm bằng `dumpsys media_session` (mục 10).
- Trong `onMediaButtonEvent`, nhận cả `MEDIA_FAST_FORWARD`/`MEDIA_REWIND`, phòng khi firmware Sony đổi mã.
  Bài review đời firmware 1.x từng ghi nhận nút next/prev không chạy với vài app.
- **Công tắc HOLD** tắt các nút ở tầng kernel (nhiều khả năng ghi vào `disabled_keys`). Khi HOLD bật, không app nào nhận được nút; cảm ứng vẫn chạy.
- **Âm lượng**, chọn một:
  - (a) **Khuyên dùng**: phím âm lượng chỉnh âm lượng của A105 (`STREAM_MUSIC`), còn Mac gửi tín hiệu đầy biên độ.
    Không bị mất độ phân giải khi đường ra chỉ 16‑bit (High‑Res streaming OFF).
  - (b) `setPlaybackToRemote(VolumeProviderCompat)`: phím âm lượng điều khiển âm lượng phía Mac.
    mediaremote-adapter không có lệnh âm lượng, nên helper phải tự đổi âm lượng của BlackHole
    (`kAudioHardwareServiceDeviceProperty_VirtualMainVolume`). Đây là giảm âm bằng số, mất bit‑exact như 4.2 đã cảnh báo,
    và cần thêm `{"t":"cmd","op":"volume","value":…}` vào giao thức. Khi đó Activity không được gọi `setVolumeControlStream(STREAM_MUSIC)`.
- **Nếu Sony chặn phím** (ở mục 10 không thấy `Sending KeyEvent … to <app của bạn>`, đích là một gói Sony,
  hoặc `dumpsys media_session` có `Media key listener`/`Global priority session` của Sony):
  1. Khi màn bật: để StatsActivity ở foreground và tự xử lý phím trong `onKeyDown`, vì cửa sổ đang focus nhận phím trước MediaSession.
  2. Khi màn tắt: accessibility service **không** giúp được, vì Android 9 bỏ qua bộ lọc phím của accessibility lúc màn tắt.
     Không root thì chỉ còn nút trên notification và màn khoá.
  3. Có root (KernelSU/APatch): daemon đọc thẳng `/dev/input/eventN` của `gpio-keys` (chạy được cả khi màn tắt) rồi chuyển lệnh cho app qua socket cục bộ.

### 4.6 Màn hình thông số

| Nhóm | Trường | Lấy từ | Cần root? |
|---|---|---|---|
| Nguồn | App đang phát, tên bài, nghệ sĩ, album, ảnh bìa, tiến độ | Helper (mediaremote-adapter) | Không |
| Nguồn | Cách bắt âm, rate thiết bị Mac, codec, bit, bitrate thực | Header + kênh điều khiển | Không |
| Mạng | RSSI, tốc độ liên kết, tần số (2.4/5 GHz) | `WifiInfo` (chỉ cần `ACCESS_WIFI_STATE`) | Không |
| Mạng | RTT, jitter, gói mất/trễ (theo seq), throughput | Kênh điều khiển + header | Không |
| Bộ đệm | Mức đầy / mục tiêu (ms), underrun, trôi đồng hồ (ppm), số lần đồng bộ lại | App | Không |
| Đầu ra | Định dạng AudioTrack, rate mixer (`PROPERTY_OUTPUT_SAMPLE_RATE`), thiết bị đang định tuyến (jack/BT), độ trễ ra | `AudioManager`, `getRoutedDevice()`, `getTimestamp()` | Không |
| Đầu ra | Codec Bluetooth (loại codec, rate, bit, chế độ chất lượng LDAC 990/660/330/ABR). Bitrate LDAC thực khi ở ABR chỉ có trong `dumpsys bluetooth_manager` | `BluetoothA2dp.getCodecStatus` qua reflection (nằm trong light greylist của Android 9) | Không (chưa thử trên A105) |
| Đầu ra | Định dạng ở đầu vào HAL (rate, bit, AudioFlinger có resample không) và định dạng ALSA thật | `dumpsys media.audio_flinger` (cần quyền DUMP: qua adb, Shizuku (trên Android 9 phải bật lại qua adb sau mỗi lần khởi động) hoặc root). HAL Sony còn có bộ resample riêng nên rate ra ALSA có thể khác: xem `logcat` tag `Alsa` (cấp `READ_LOGS` một lần bằng `adb shell pm grant`; tag này mới thấy trên firmware 1.x) hoặc `/proc/asound/card1/pcm0p/sub0/hw_params` (root) | adb hoặc root |
| Đầu ra | Trạng thái High‑Res streaming | Suy ra từ rate mixer, hoặc `getprop persist.vendor.audio.mixerthread.res` (suy luận) | Không |
| Máy | Pin %, đang sạc, nhiệt độ pin, điện áp, dòng | `ACTION_BATTERY_CHANGED`, `BatteryManager` | Không |
| Máy | Nhiệt độ SoC | `/sys/class/thermal` (SELinux có thể chặn app thường) | Có thể |
| Âm thanh | VU L/R, peak, phổ 32 băng | FFT trong app | Không |

Bố cục gợi ý cho màn 720×1280:

```
┌────────────────────────────────────┐
│ ● ĐANG PHÁT        MacBook › A105   │
│ ┌──────┐  Tên bài                   │
│ │ bìa  │  Nghệ sĩ · Album           │
│ └──────┘  ▶ 01:23 ━━━━━━○──── 04:56  │
├────────────────────────────────────┤
│ NGUỒN  Spotify · FLAC 48k/24 · 1.9Mb│
│ MẠNG   5 GHz · −52 dBm · 433 Mb/s   │
│        RTT 4 ms · jitter 1.2 ms     │
│        mất 0 · trễ 0                │
│ ĐỆM    ████████░░ 482/500 ms        │
│        trôi +41 ppm · underrun 0    │
│ RA     float 48k → HAL 48k/16 (OFF) │
│        jack 3.5 mm · Direct Source  │
│ MÁY    82% ⚡ · pin 36.4 °C · 4.11 V │
├────────────────────────────────────┤
│ L ▮▮▮▮▮▮▮▮▯▯ −12 dB                 │
│ R ▮▮▮▮▮▮▮▯▯▯ −14 dB                 │
│ ▁▂▄▆█▇▅▃▂▁▂▃▅▆▅▃▂▁▁▂▃▂▁ (phổ)        │
├────────────────────────────────────┤
│   ⏮        ⏯        ⏭      🔈 ▬▬▬○  │
└────────────────────────────────────┘
```

Màn hình bật liên tục tốn pin nên dùng nền tối và cho phép tắt màn (âm thanh và nút cứng vẫn chạy khi màn tắt).

### 4.7 Thư viện và giấy phép

| Thành phần | Giấy phép | Ghi chú |
|---|---|---|
| libFLAC | BSD‑like (Xiph) | Công cụ dòng lệnh `flac` là GPL, thư viện thì không |
| speexdsp (resampler) | BSD‑3 | |
| libsamplerate | BSD‑2 | Thay thế speexdsp |
| Oboe | Apache‑2.0 | Chỉ I16/Float trên API 28 |
| Snapcast / Snapdroid | GPL‑3.0 | Dùng lại code thì app phải GPL‑3.0 |
| jqssun AirPlay | GPL‑3.0 | Có thể fork để thêm màn hình thông số |
| roc‑toolkit / roc‑droid | MPL‑2.0 | roc‑java: MIT |
| BlackHole | GPL‑3.0 | |
| mediaremote-adapter | Kiểm tra repo trước khi đóng gói | |

Ghép Oboe + libFLAC + speexdsp thì app có thể giữ giấy phép tuỳ ý.

### 4.8 Lộ trình build

> **Đã có mã nguồn MVP (M1–M3, một phần M4)** trong [`walkdac/`](../walkdac/README.md): sender Python cho Mac, app Kotlin cho A105,
> test tự động và hướng dẫn chạy. Chưa thử trên máy thật.

1. **M0 – Kiểm tra trên máy** (mục 10): chip Wi‑Fi, nút cứng, đường âm thanh với High‑Res streaming ON/OFF, iperf3 trên 5 GHz khi tắt màn.
2. **M1 – Phát được**: helper dòng lệnh (có thể viết Python/Swift) đọc BlackHole và gửi PCM 48/16 qua TCP. App A105 nhận và phát bằng AudioTrack.
3. **M2 – Ổn định**: bộ đệm jitter, bù trôi PI, đồng bộ lại cứng, foreground service, tự kết nối lại, Bonjour.
4. **M3 – Điều khiển**: kênh WebSocket, MediaSession + nút cứng, now playing + ảnh bìa từ mediaremote-adapter.
5. **M4 – Thông số và hi‑res**: FLAC qua NDK, 24‑bit/96/192 kHz khi bật High‑Res streaming, màn hình thông số đầy đủ, VU/phổ.
6. **M5 – Hoàn thiện**: app menu bar trên Mac, chọn rate tự động, process tap (macOS 14.2+).
7. **M6 (tuỳ chọn, root)**: chế độ bit‑perfect (mục 8), đọc định dạng ALSA thật lên màn hình.

---

## 5. Chất lượng âm thanh thực tế trên A105

Theo Sony (trích đoạn Help Guide; trang gốc bị chặn khi nghiên cứu), có từ firmware 2.00.05 (28/05/2020).
Một bản `dumpsys` trên ZX507 (firmware ≥ 2.00.05, trích đoạn bài review của Porta‑Fi, không phải A105 4.06) khớp với chế độ ON:
192 kHz, HAL format PCM 32‑bit. Sony cũng ghi chức năng này không hỗ trợ mọi app stream.

| *Settings › Sound › High‑Res streaming* | App bên thứ ba ra HAL dưới dạng | Gợi ý rate gửi từ Mac |
|---|---|---|
| OFF | Hạ về **48 kHz/16‑bit** | 48 kHz (tránh thêm một bước 44.1→48) |
| ON | Nâng lên **192 kHz/32‑bit** (pin giảm ~20%) | 48, 96 hoặc 192 kHz (tỉ lệ nguyên), hoặc 192 kHz để bỏ hẳn bước resample |

- Phải **khởi động lại** sau khi đổi công tắc (theo báo chí Nhật). Công tắc chỉ áp cho đường có dây.
- Bộ resample của AudioFlinger (Android 9) cho rate ≥ 40 kHz: 64 tap, chặn dải 90 dB. Khi chuyển float sang 16‑bit thì làm tròn, không dither.
- **Hiệu ứng Sony nằm trong HAL** (EQ 10 băng, DSEE HX, ClearAudio+, DC Phase Linearizer, Dynamic Normalizer, Vinyl Processor), nên áp lên mọi app nếu đang bật.
  - DSEE Ultimate chỉ chạy trong app Music player; với app khác nó thành DSEE HX.
  - *Direct Source* bỏ qua toàn bộ hiệu ứng.
  - Chưa rõ giá trị mặc định từ nhà máy của từng hiệu ứng.
- Chưa có bằng chứng 24‑bit/96 kHz qua đường này nghe khác 16/44.1. AirPlay ALAC 16/44.1 mất rất ít khi công tắc đang OFF.
- Phần cứng không phải nút thắt: driver codec CXD3778GF nhận PCM 8–384 kHz (machine driver của i.MX thu hẹp còn 11.025–384 kHz)
  và DSD trên đường ICX/DAC, qua ALSA `hires-out`. Giới hạn chỉ nằm ở phần mềm Sony (mục 8).

---

## 6. Ra loa Bluetooth: Mac → Wi‑Fi → A105 → LDAC

- Âm thanh của app nhận đi qua đường A2DP bình thường của Android nên ra được LDAC/aptX HD.
- Theo logic AOSP Android 9, đầu ra A2DP mở ở rate cao nhất mà codec cho phép **(chưa kiểm chứng với firmware Sony)**:
  - LDAC (nếu loa nhận LDAC 96 kHz): 96 kHz, PCM 32‑bit; AudioFlinger nâng âm thanh app lên 96 kHz. Loa chỉ nhận 44.1/48 kHz thì đầu ra mở ở rate đó.
  - aptX HD: 48 kHz/24‑bit.
  - Có thể ép 48 kHz trong Developer options để khớp luồng 48 kHz từ Mac.
- LDAC mặc định ở chế độ thích ứng (ABR: 990/660/492/396/330 kbps).
- Bluetooth cộng thêm khoảng **0.2–0.35 s**, chủ yếu do bộ đệm của loa.
- Khi đang nối Bluetooth, DSEE Ultimate, DC Phase Linearizer và Vinyl Processor bị tắt.
- **Dùng Wi‑Fi 5 GHz.** QCA9377 dùng chung ăng‑ten giữa Wi‑Fi và Bluetooth bằng chia thời gian (theo trích đoạn tài liệu Qualcomm).
  Chuyển sang 5 GHz bỏ được phần tranh chấp phổ tần 2.4 GHz, nhưng chưa rõ ăng‑ten có còn chia thời gian khi Wi‑Fi ở 5 GHz hay không.
  Hãy thử A/B 2.4 và 5 GHz, đọc bộ đếm dropouts trong `dumpsys bluetooth_manager` (mục 10). Sony cũng khuyên tắt Wi‑Fi khi BT bị ngắt tiếng.
  Hàng đợi gửi BT tối đa 28 gói, tràn thì xả hết, nghe thành một lần mất tiếng.

---

## 7. Bluetooth từ Mac vào A105 (A2DP sink), chỉ khi root

Cách root A105: README mục 5. Unlock bootloader **xoá sạch dữ liệu**; dùng KernelSU hoặc APatch, Magisk không chạy trên máy này.

**Khả thi về mã nguồn, nhưng có ba giới hạn cứng:**

1. Android 9 **không chạy được nguồn và đích A2DP cùng lúc** (`btif_av.cc` luôn ưu tiên vai trò nguồn).
   Bật sink phải tắt source, nên chỉ có Mac → A105 → jack 3.5 mm, không ra được loa Bluetooth. Android 14 mới cho chạy song song.
2. Bản ghi SDP "Audio Sink" chỉ được tạo nếu `libbluetooth.so` được biên dịch với `BTA_AV_SINK_INCLUDED=TRUE`.
   AOSP mặc định FALSE, BSP của NXP cho i.MX 8M Mini đặt TRUE. **Sony giữ giá trị nào thì chưa biết.**
   Nếu là FALSE, Mac sẽ không bao giờ thấy A105 là loa. Thay `libbluetooth.so` tự build thì rủi ro hỏng LDAC và các sửa đổi của Sony.
3. Chất lượng: sink của Android 9 chỉ giải mã **SBC (bitpool ≤ 53) và AAC**, 16‑bit, 44.1/48 kHz. Trễ khoảng 100–220 ms.

Cách thử rẻ nhất (cần KernelSU/APatch, **chưa ai thử trên A105**):

```
# 1) module KernelSU chứa một RRO tĩnh trong /vendor/overlay nhắm com.android.bluetooth:
#    profile_supported_a2dp_sink=true, profile_supported_avrcp_controller=true,
#    profile_supported_a2dp=false, profile_supported_avrcp_target=false
# 2) tắt A2DP source (4) + AVRCP target (8192):
adb shell "su -c 'settings put global bluetooth_disabled_profiles 8196'"
# 3) bật các component (giá trị android:enabled trong manifest có thể không ăn theo overlay):
adb shell "su -c 'pm enable com.android.bluetooth/.a2dpsink.A2dpSinkService'"
adb shell "su -c 'pm enable com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService'"
adb shell "su -c 'pm enable com.android.bluetooth/.avrcpcontroller.AvrcpControllerService'"
# 4) tắt/bật Bluetooth. Từ một máy Linux: `bluetoothctl info <địa chỉ BT của A105>` (hoặc `sdptool browse`) phải thấy
#    Audio Sink (UUID 0000110b). Không thấy → cờ BTA_AV_SINK_INCLUDED là FALSE → dừng ở đây.
#    Đừng dựa vào UUID trong `dumpsys bluetooth_manager`: UUID báo lên Java không phụ thuộc cờ này.
#    Có bản ghi SDP mà Mac vẫn không liệt kê A105 trong Sound Output → có thể do Class of Device 'smartphone' (cố định lúc biên dịch).
```

- **App đi kèm là bắt buộc.** Trên máy không phải TV, Android 9 gửi lệnh AVRCP PAUSE về Mac khi luồng bắt đầu mà chưa có audio focus.
  App phải nối `MediaBrowser` tới `com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService` và gọi `prepare()` ngay khi Mac kết nối.
- Cùng MediaSession đó cho play/pause/next/prev về Mac và metadata (tên bài, nghệ sĩ, album, thời lượng), **không có ảnh bìa** (BIP có từ Android 11).
  Phía Android 9 hỗ trợ âm lượng tuyệt đối (SetAbsoluteVolume 0–127 → `STREAM_MUSIC`).
  Chưa có nguồn nào xác nhận macOS dùng âm lượng tuyệt đối hay gửi metadata AVRCP cho loa không phải của Apple;
  việc macOS nhận play/pause/next từ loa cũng chỉ dựa trên trích đoạn diễn đàn. Thử trước với một sink BlueZ và `btmon`.
- Thông số chỉ có sample rate và số kênh (API ẩn). Muốn biết codec/bitpool/bitrate thì root và phân tích `btsnoop_hci.log`.
- Đóng gói thành module gỡ được, kèm script chuyển chế độ, vì bật sink thì mất tai nghe Bluetooth.
  Sao lưu `/system/app/Bluetooth` (hoặc `priv-app`) và `/system/lib64/libbluetooth*.so` trước.

---

## 8. Chế độ DAC bit‑perfect (root)

Cách root A105: README mục 5. Unlock bootloader **xoá sạch dữ liệu**; dùng KernelSU hoặc APatch, Magisk không chạy trên máy này.

- Card ALSA `imx-audio-cxd3778gf` có ba đầu ra phát:
  - `pcmC1D0p` "hires-out" (SAI3)
  - `pcmC1D1p` "standard" (SAI5)
  - `pcmC1D2p` "hires-out_low_power" (qua lõi M4)
- Machine driver cho phép 11.025–384 kHz (44.1, 48, 88.2, 96, 176.4, 192, 352.8, 384…) và S16/S24/S32 (thêm DSD trên đường ICX/DAC).
- Về nguyên tắc, daemon root (tinyalsa) ghi thẳng vào `pcmC1D0p` sẽ **bỏ qua AudioFlinger lẫn hiệu ứng Sony**, giữ đúng rate của luồng.
  **Chưa ai công bố đã thử trên máy.** Số card `card1` chỉ lấy từ một bản dump; mã nguồn không cố định nó (xem `/proc/asound/cards`).
  Đổi lại phải tự chỉnh âm lượng và định tuyến bằng mixer (`tinymix`), và chỉ mở được PCM khi HAL của Sony không giữ nó.
- Dự án cộng đồng He0xD4C0/A100_ZX500_USB-DAC (2026) dùng đúng cách này cho chế độ USB DAC. Dự án **chưa được kiểm chứng**:
  - không có bản phát hành
  - kernel 4.14 chỉ cho `f_uac2` một rate cố định
  - không có feedback endpoint nên trôi đồng hồ sẽ gây tiếng click
  - daemon cầu nối có lỗi khiến không khởi động được nếu không sửa
- Với WalkDAC, chế độ này là một nhánh xuất thay cho AudioTrack. Bộ đệm, bù trôi, điều khiển và màn hình thông số giữ nguyên;
  màn hình còn đọc được `/proc/asound/card1/pcm0p/sub0/hw_params` để hiện định dạng thật.

---

## 9. Wi‑Fi, nguồn và nhiệt khi chạy 24/7

**Wi‑Fi (QCA9377, driver `qcacld-2.0_sony`, module `wlan`):**
- Android 9 chỉ dùng `WIFI_MODE_FULL_HIGH_PERF` để thống kê pin. Thêm vào đó, lệnh `SETSUSPENDMODE` trong driver của Sony là một khối rỗng.
  Kết quả là khoá này và việc tắt màn đều **không đổi** power save 802.11.
- Firmware tự ra khỏi power save khi có lưu lượng và quay lại sau một khoảng nghỉ ngắn (theo cấu hình mẫu: 20–200 ms).
  Luồng unicast đều đặn (khối ≤ 20 ms) giữ radio thức kể cả khi tắt màn.
- Điều thật sự gây hại là kernel ngủ khi không ai giữ wake lock. Vì vậy app phải giữ `PARTIAL_WAKE_LOCK`. AudioTrack đang phát thường cũng giữ một cái.
- Root: firmware gốc không có `iw`. Cài qua Termux (`pkg install root-repo && pkg install iw`), rồi chạy
  `su -c "$PREFIX/bin/iw dev wlan0 set power_save off"` (kiểm tra bằng `… get power_save`).
  Android 9 bật lại power save mỗi lần nối lại Wi‑Fi nên phải chạy lại sau mỗi lần kết nối. Chưa thử tác dụng phụ.
- Nếu RTT có các đỉnh trễ theo bậc ~102 ms × DTIM thì đó là dấu hiệu của power save.

**Nguồn và nhiệt:**
- IC sạc BQ25898 sạc ở 960 mA tới 4.176 V. Khi pin "ấm" (khoảng 42–43.4 °C) thì giảm còn 128 mA/4.048 V.
- Sony cảnh báo máy nóng khi vừa sạc vừa chạy app, và sẽ ngừng sạc nếu quá nóng. Đặt máy thoáng.
- Hai nguồn nghiên cứu mâu thuẫn về việc A100 có *Battery Care* (giới hạn sạc). Xem Settings › Battery trên máy của bạn.
- Root: driver sạc có thuộc tính sysfs `diag_vbus_sink_current`: `0` = ngừng hút dòng USB, `500` = giới hạn 500 mA, `-1` = bình thường.
  Có thể viết script giữ pin trong khoảng 60–80% cho máy cắm điện 24/7 (tìm đường dẫn bằng `find /sys -name diag_vbus_sink_current`).
- Bật High‑Res streaming tốn thêm khoảng 20% pin. Bluetooth có thể giảm tới 60% thời lượng pin.

---

## 10. Kiểm tra trên máy thật

Trên Mac, chạy `setopt interactivecomments` trước khi dán (xem đầu mục 3). Các lệnh `dns-sd -B`, `tcpdump`, `ping`, `logcat -s`
chạy liên tục, nên mở mỗi lệnh trong một cửa sổ Terminal riêng.

```
# --- Chip Wi‑Fi: mong đợi "wlan" (Qualcomm) và vendor 0x0271
adb shell lsmod | grep -E 'wlan|brcmfmac'
adb shell 'cat /sys/bus/sdio/devices/*/vendor /sys/bus/sdio/devices/*/device'

# --- Nút cứng: thử với HOLD tắt/bật, màn bật/tắt
# tìm thiết bị "gpio-keys" rồi bấm từng nút
adb shell getevent -lp
adb shell getevent -lt /dev/input/eventN
adb shell cat /sys/devices/platform/gpio-keys/disabled_keys
adb shell dumpsys media_session | grep -E 'Media button session|Media key listener|Global priority'
# phát nhạc bằng một app bên thứ ba rồi bấm nút; mong đợi "Sending KeyEvent ... to <package>"
adb logcat -s MediaSessionService:D

# --- Đường âm thanh: phát một file 44.1 kHz và một file 96 kHz bằng app bên thứ ba
adb shell dumpsys media.audio_flinger | grep -nE 'Output thread|Sample rate|HAL format|Processing format|Output device'
adb shell getprop persist.vendor.audio.mixerthread.res
adb logcat -d | grep -E 'Alsa|HighRes|StdRes'
# [root] định dạng ALSA thật
adb shell 'su -c "cat /proc/asound/cards; for d in 0 1 2; do echo pcm\$d; cat /proc/asound/card1/pcm\${d}p/sub0/hw_params; done"'

# --- Mạng, 30 phút, màn A105 tắt
# A105 (Termux): pkg install iperf3; termux-wake-lock; iperf3 -s
brew install iperf3
iperf3 -c <IP_A105> -u -b 5M -l 1200 -t 1800 -i 1 --get-server-output
sudo ping -i 0.02 <IP_A105> | tee rtt.txt
adb shell dumpsys wifi | grep -m1 mWifiInfo

# --- Doze và tắt màn với chính app nhận (sau M1), 30–60 phút
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
adb shell cmd appops get <pkg> RUN_ANY_IN_BACKGROUND
# xong thì trả lại:
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset

# --- AirPlay: Mac có gửi DACP và metadata không (mục 3.1)
dns-sd -B _dacp._tcp local.
sudo tcpdump -i en0 -l -A -s 0 'tcp port 7000 and host <IP_A105>' \
  | grep -E --line-buffered '(GET|POST|SETUP|RECORD|SET_PARAMETER|FLUSH|TEARDOWN) [^ ]+ RTSP/1\.0|^(DACP-ID|Active-Remote|Content-Type|User-Agent):'
dns-sd -L iTunes_Ctrl_<DACP-ID> _dacp._tcp local.
curl -sv -H "Active-Remote: <giá trị từ tcpdump>" http://<IP_Mac>:<cổng>/ctrl-int/1/nextitem
adb logcat -s AirPlayService:V DacpController:V AirPlayNative:V

# --- Bluetooth ra loa: codec, tốc độ thật và số lần mất tiếng
adb shell dumpsys bluetooth_manager | grep -E -A40 'A2DP (Codecs )?State:'

# --- Now Playing trên Mac
media-control get -h
```

Cách đọc phần AirPlay:
- Dòng request trong tcpdump có vài ký tự rác ở đầu (header TCP), không sao.
- Đạt khi `DACP-ID` và `Active-Remote` nằm dưới dòng SETUP ngay trước RECORD. Nếu chỉ nằm dưới `GET /info` thì jqssun không nhận.
- Có `Content-Type: application/x-dmap-tagged`, `image/jpeg` hoặc `text/parameters` dưới SET_PARAMETER nghĩa là Mac gửi metadata.
- `curl` trả HTTP 204 và app Now Playing trên Mac chuyển bài nghĩa là Mac nhận lệnh DACP. Thử thêm `playpause`, `volumeup`.

Đọc kết quả trước khi viết code:
1. **Mạng**: p99.9 RTT dưới ~60 ms và không có đợt mất gói dài hơn 20 ms (ngưỡng kinh nghiệm) là "mạng sạch",
   có thể hạ bộ đệm xuống 200–300 ms. Có đỉnh trễ theo bậc ~102 ms × DTIM thì kiểm tra wake lock và nhịp gửi ≤ 20 ms,
   giữ 400–500 ms (hoặc root tắt power save, mục 9). Mất gói thành từng đợt dài thì giữ TCP và 500 ms trở lên.
2. **Nút cứng**: thấy `Sending KeyEvent … to <app thử>` cả khi màn tắt thì không cần root. Ra mã FAST_FORWARD/REWIND thì map trong
   `onMediaButtonEvent`. Không có dòng nào thì làm theo cuối mục 4.5.
3. **Đường âm thanh**: HAL 48000 Hz/16‑bit thì gửi 48 kHz. HAL 192000 Hz/32‑bit thì gửi 48/96/192 kHz.
4. **Doze**: app vẫn phát sau 30–60 phút force‑idle, và `RUN_ANY_IN_BACKGROUND` phải là `allow`.

---

## Nguồn

- Sony GPL kernel (DTS, `walkman.config`, `qcacld-2.0_sony`, `imx-cxd3778gf.c`, `gpio_keys.c`, `hold_switch.c`): [97lily/2019_android_walkman](https://github.com/97lily/2019_android_walkman)
- Chuỗi trong HAL âm thanh Sony, đề án USB DAC: [He0xD4C0/A100_ZX500_USB-DAC](https://github.com/He0xD4C0/A100_ZX500_USB-DAC) · log ALSA đời đầu: [unstabler/NWAlsaInspect](https://github.com/unstabler/NWAlsaInspect)
- AirPlay trên Android: [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) · [UxPlay #570 (FairPlay loại 2)](https://github.com/FDH2/UxPlay/issues/570) · [shairport-sync AIRPLAY2.md](https://github.com/mikebrady/shairport-sync/blob/master/AIRPLAY2.md)
- Trễ thấp: [roc-vad](https://github.com/roc-streaming/roc-vad) · [roc-droid](https://github.com/roc-streaming/roc-droid) · [SonoBus](https://github.com/sonosaurus/sonobus)
- Snapcast: [snapcast/snapcast](https://github.com/snapcast/snapcast) (`doc/configuration.md`, `client/stream.cpp`) · [badaix/snapdroid](https://github.com/badaix/snapdroid)
- Mac: [BlackHole](https://github.com/ExistentialAudio/BlackHole) · [Apple: Core Audio taps](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps) · [ungive/mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) · [ungive/media-control](https://github.com/ungive/media-control)
- Android: [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack) · [AudioFormat](https://developer.android.com/reference/android/media/AudioFormat) · [Media buttons](https://developer.android.com/media/legacy/media-buttons) · [Oboe](https://github.com/google/oboe)
- Bluetooth Android 9 (LineageOS 16.0 = AOSP 9): [android_system_bt](https://github.com/LineageOS/android_system_bt/tree/lineage-16.0) · [android_packages_apps_Bluetooth](https://github.com/LineageOS/android_packages_apps_Bluetooth/tree/lineage-16.0)
