# WalkDAC – Mac → Wi‑Fi → NW‑A105 → loa 3.5 mm

Bản MVP của thiết kế trong [`docs/a105-wifi-dac.md`](../docs/a105-wifi-dac.md) (mục 4). Hai phần:

| Phần | Chạy ở đâu | Ngôn ngữ | Làm gì |
|---|---|---|---|
| `sender/walkdac_sender.py` | Mac | Python 3 | Đọc âm thanh hệ thống từ BlackHole, gửi PCM 24‑bit qua TCP, trả lời đồng bộ thời gian, nhận lệnh play/next… và chuyển cho `media-control`, phát tên bài/ảnh bìa, phát beacon UDP để A105 tự tìm |
| `android/` | NW‑A105 | Kotlin | App nhận: foreground service, bộ đệm jitter 450 ms, bù trôi đồng hồ ±500 ppm, AudioTrack float, MediaSession (nút cứng), thông báo, màn hình thông số |

Trạng thái: **chưa chạy thử trên máy thật**. Phần đã kiểm chứng tự động: sender (11 test: loopback, định dạng khung, kênh điều khiển, metadata, beacon), lõi Kotlin (17 test, gồm mô phỏng 10 phút lệch đồng hồ ±150 ppm, mất mạng, đổi bộ đệm, flush đồng thời), và app biên dịch sạch với framework Android 9. Mục 6 liệt kê những gì còn thiếu.

```
walkdac/
├── sender/            walkdac_sender.py, requirements.txt, tests/
├── android/           project Android Studio: core/ (Kotlin thuần, có test) + app/
└── tools/compilecheck Gradle project để chạy test lõi và kiểm tra biên dịch app mà không cần Android SDK
```

---

## 1. Chuẩn bị trên Mac (một lần)

```
brew install blackhole-2ch media-control
python3 -m venv ~/.walkdac-venv
source ~/.walkdac-venv/bin/activate        # chạy lại dòng này mỗi lần mở Terminal mới
pip install -r sender/requirements.txt
```

Python của Homebrew (3.12+) chặn `pip install` toàn hệ thống (PEP 668), nên dùng venv như trên.
Kiểm tra media-control: `media-control test` phải thoát mã 0, `media-control get -h` phải in bài đang phát.

1. **Audio MIDI Setup** (Spotlight › "Audio MIDI Setup"): chọn *BlackHole 2ch*, đặt Format = **48000 Hz**.
   Đây là rate sẽ gửi sang A105. (96000 chỉ có ý nghĩa khi bật *High‑Res streaming* trên A105, xem mục 5 của tài liệu thiết kế.)
2. **System Settings › Sound › Output** = *BlackHole 2ch*. Loa của Mac sẽ câm; đó là chủ ý. Giữ âm lượng Mac ở **100 %**, chỉnh âm lượng trên A105.
3. Mở Terminal, chạy `setopt interactivecomments` nếu định dán các dòng có `#`.

## 2. Chạy sender

```
cd walkdac/sender
python3 walkdac_sender.py --list-devices          # phải thấy "BlackHole 2ch"
python3 walkdac_sender.py -v                      # mặc định: --device "BlackHole 2ch" --format s24 --block-ms 10
```

Lần đầu macOS sẽ hỏi hai quyền cho **Terminal**: *Microphone* (sounddevice đọc BlackHole như đầu vào) và *Local Network* (macOS 15+).
Phải cho phép cả hai. Từ chối Microphone thì sender vẫn chạy nhưng chỉ gửi im lặng.

Tuỳ chọn hay dùng:

| Cờ | Ý nghĩa |
|---|---|
| `--source sine` | Phát tín hiệu 1 kHz thay cho BlackHole, để thử đường mạng và app mà không cần cài gì |
| `--rate 96000` | Ép rate (mặc định: rate hiện tại của BlackHole trong Audio MIDI Setup) |
| `--format s16` / `f32` | PCM 16‑bit (1.5 Mb/s ở 48 kHz) hoặc float 32 (3.1 Mb/s). Mặc định s24 = 2.3 Mb/s |
| `--name "MacBook"` | Tên hiện trên A105 |
| `--no-flush-on-change` | Không bảo A105 xả bộ đệm khi đổi bài trên Mac |
| `--no-media-control` | Không dùng media-control (không có tên bài, không điều khiển ngược) |

Sender in log mỗi 5 s khi chưa có client, và mỗi lần A105 kết nối/ngắt. Với `-v` còn in thống kê A105 gửi về (bộ đệm, underrun, RSSI).
Sender tự cảnh báo khi luồng bắt âm đứng (5 s không có khối nào) và khi đang có A105 nghe mà tín hiệu toàn số 0 (Output chưa là BlackHole, hoặc Terminal thiếu quyền Microphone).

**Đổi rate giữa chừng**: bản này đọc rate của BlackHole lúc khởi động. Đổi trong Audio MIDI Setup thì tắt và chạy lại sender.

## 3. Build và cài app lên A105

1. Mở thư mục `walkdac/android` bằng **Android Studio** (Hedgehog trở lên). Lần đầu nó tải AGP 8.7.3, Kotlin 2.0.21 và SDK 35.
2. *Build › Build Bundle(s) / APK(s) › Build APK(s)* → `app/build/outputs/apk/debug/app-debug.apk`.
   Hoặc dòng lệnh: `./gradlew assembleDebug`.
3. Trên A105: *Settings › System › About › bấm Build number 7 lần* → *Developer options › USB debugging*. Cắm USB:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Mở **WalkDAC**. App tự bật dịch vụ nền và tìm Mac qua beacon. Nếu 10 giây vẫn "Đang tìm Mac…", nhập IP của Mac
   (`ipconfig getifaddr en0` trên Mac) rồi bấm **Kết nối**.
5. *Settings › Apps › WalkDAC › Battery*: không được để *Background restriction*. Nên thêm vào whitelist Doze:
   ```
   adb shell dumpsys deviceidle whitelist +dev.walkdac.receiver
   ```
6. Cắm loa/ampli vào jack 3.5 mm. Âm lượng chỉnh bằng phím của A105 (app đặt phím âm lượng vào `STREAM_MUSIC`).

Mac và A105 phải cùng mạng Wi‑Fi, ưu tiên **5 GHz**. Beacon là UDP broadcast nên không đi qua router cách ly khách (guest isolation).

## 4. Dùng

- **Nút cứng** ⏯ ⏭ ⏮ của A105 và nút trên thông báo/màn khoá gửi lệnh sang Mac qua `media-control`. Lệnh đến app mà macOS đang coi là "Now Playing" (app bắt đầu phát gần nhất).
  Khi bấm pause/next/prev, app xả bộ đệm ngay nên phản hồi tức thì; nếu Mac không đổi gì (ví dụ không có app nào đang phát) thì chỉ mất khoảng nửa giây nhạc.
  Bài chuyển tự nhiên trên Mac thì **không** bị cắt: A105 phát nối tiếp, chỉ trễ đúng bằng bộ đệm.
- **Màn hình** hiện: nguồn (tên Mac, codec, rate, bitrate thật), mạng (băng tần, RSSI, tốc độ liên kết, RTT, jitter, độ trễ mạng), bộ đệm (mức đầy/mục tiêu, trôi ppm, số frame chèn/bỏ, underrun, resync), đầu ra (rate AudioTrack → rate mixer của Sony, thiết bị đang định tuyến, trễ ra), tổng trễ ước tính, pin/nhiệt độ/điện áp/dòng.
- **Bộ đệm mục tiêu**: thanh trượt 200–2000 ms, có tác dụng ngay (hạ thì cắt bớt, tăng thì im lặng ngắn để nạp thêm). Bắt đầu 450 ms; mạng sạch (RTT ổn định, không underrun sau 30 phút) thì hạ dần. Trễ tổng ≈ mạng + bộ đệm + trễ ra (~ 20–40 ms).
- **High‑Res streaming** (Settings › Sound của A105): OFF → Sony hạ về 48 kHz/16‑bit, ON → nâng lên 192 kHz/32‑bit (phải khởi động lại). Dòng "RA" trên màn hình cho thấy rate mixer hiện tại.
- Bật *Direct Source* trong app Sound adjustment của Sony nếu không muốn EQ/DSEE HX chạm vào tín hiệu.

## 5. Chẩn đoán

```
# A105
adb logcat -s WalkDAC.Service:V WalkDAC.Audio:V WalkDAC.Control:V WalkDAC.Discovery:V AudioTrack:W
adb shell dumpsys media_session | grep -A3 WalkDAC         # phiên có nhận nút cứng không
adb shell dumpsys media.audio_flinger | grep -E 'Sample rate|HAL format'   # rate thật tới HAL
# Mac
python3 walkdac_sender.py -v --source sine                  # loại trừ BlackHole/quyền Microphone
media-control get -h                                        # media-control có đọc được Now Playing không
```

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| A105 "Đang tìm Mac…" mãi | Khác mạng/VLAN, router chặn broadcast, tường lửa Mac. Sender gửi beacon tới broadcast của mọi giao diện mạng (kể cả khi có VPN); vẫn không thấy thì nhập IP tay |
| Kết nối được nhưng im lặng, bitrate ~2300 kb/s | Mac chưa chọn BlackHole làm Output, hoặc Terminal chưa có quyền Microphone |
| Kết nối được, bitrate 0 | Sender chưa chạy hoặc tường lửa Mac chặn cổng 7700 |
| Underrun lặp lại, resync | Wi‑Fi 2.4 GHz hoặc sóng yếu: chuyển 5 GHz, tăng bộ đệm |
| "trôi" bị kẹt ở ±500 ppm | Rate Mac và A105 lệch nhiều (ví dụ nguồn 44.1 kHz nhưng header báo 48 kHz). Kiểm tra Audio MIDI Setup |
| Nút cứng không có tác dụng | Xem `dumpsys media_session`; đọc cuối mục 4.5 tài liệu thiết kế |
| Không có tên bài | Sender in "media-control self-test failed" lúc khởi động: `brew upgrade media-control`, rồi `media-control test` |
| Nhạc dừng khi tắt màn | App bị *Background restriction*; thêm whitelist Doze (mục 3) |

## 6. Chưa có trong MVP

- FLAC (đang gửi PCM 24‑bit thô, 2.3 Mb/s ở 48 kHz; thừa với 5 GHz).
- Tự theo rate khi đổi trong Audio MIDI Setup (sender cần chạy lại).
- Âm lượng từ A105 điều khiển Mac (cố ý: chỉnh trên A105 giữ tín hiệu Mac ở 100 %).
- Bonjour (đang dùng UDP broadcast), tự chạy lại sau khi khởi động máy, chọn giữa nhiều Mac.
- Chế độ bit‑perfect (cần root, mục 8 tài liệu thiết kế).

## 7. Chạy test (không cần Mac hay A105)

```
cd walkdac/sender && python3 -m unittest discover -s tests -v   # 11 test; cần numpy, sounddevice không bắt buộc
cd walkdac/tools/compilecheck && gradle :core:test :app:compileKotlin
```
