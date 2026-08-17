# JoyMerge Quest

Merges a **left and a right Nintendo Joy-Con into one virtual gamepad** on a
Meta Quest 2, so that Android apps — Xbox Cloud Gaming in particular — see a
single controller instead of two half-controllers.

```
Joy-Con L  ─┐
            ├─►  JoyMerge Quest  ─►  merged virtual gamepad  ─►  Xbox Cloud Gaming
Joy-Con R  ─┘     (calibrate → merge → publish)
```

Native Android app: Kotlin, Jetpack Compose, one small C layer for the Linux
input ioctls. **No root.** It needs [Shizuku](https://shizuku.rikka.app/) for
the parts that require shell privileges, and it tells you plainly when it does
not have them.

---

## Read this first

This repository contains a **complete, building implementation** that has **not
been tested on a physical Quest 2**, because that requires the hardware. Every
claim below is labelled with what is actually known. In particular:

> **The single biggest unknown is whether the shell user on Horizon OS is
> allowed to open `/dev/uinput`.** SELinux policy on the Quest may forbid it.
> The app is built to find out and tell you exactly what happened, and it ships
> a second backend that does not need uinput at all.

The app never claims a working controller it did not create. If `/dev/uinput`
returns `Permission denied`, the Diagnostics screen says
`UINPUT BLOCKED: Permission denied` and the virtual gamepad stays **INACTIVE**.

---

## Guia rápido (Português)

1. **Baixe o APK**: GitHub → aba **Actions** → último build verde → seção
   **Artifacts** → **JoyMergeQuest-APK** → baixe e descompacte o
   `JoyMergeQuest-debug.apk`. Funciona pelo iPad.
2. **Instale no Quest 2** com SideQuest ou `adb install JoyMergeQuest-debug.apk`.
3. **Pareie os dois Joy-Cons** nas configurações de Bluetooth do Quest
   (segure o botão de sincronismo na lateral de cada Joy-Con).
4. **Instale e inicie o Shizuku** (veja *Setting up Shizuku* abaixo). É
   necessário rodar um comando `adb` uma vez a cada reinicialização do headset.
5. Abra o **JoyMerge Quest** → `GRANT SHIZUKU PERMISSION` →
   `CONNECT PRIVILEGED SERVICE`.
6. **`CALIBRATE JOY-CONS`** e siga as instruções na tela (aperte cada botão que
   for pedido). Salve o perfil.
7. **`GAMEPAD TESTER`** para confirmar que os dois Joy-Cons estão sendo
   combinados corretamente.
8. **`START VIRTUAL GAMEPAD`**, minimize o app e abra o Xbox Cloud Gaming.
9. Se algo falhar: **`DIAGNOSTICS`** → `REFRESH` → `COPY LOG` / `EXPORT LOG`.
   O erro real aparece ali (por exemplo `UINPUT BLOCKED: Permission denied`).

O restante deste documento explica cada passo em detalhe, em inglês — que é
também o idioma da interface do aplicativo.

---

## Quest 2 compatibility status

| Capability | Status | Notes |
|---|---|---|
| Builds an `arm64-v8a` debug APK in CI | **IMPLEMENTED** | Verified: `assembleDebug` + native `libjoymerge.so` build on every push. |
| Merge engine (two input streams → one pad) | **IMPLEMENTED** | Covered by 36 JVM unit tests that run in CI. |
| Calibration profile save/load | **IMPLEMENTED** | Round-trip covered by unit tests. |
| Joy-Con detection by VID/PID and name | **IMPLEMENTED** · **NEEDS HARDWARE TEST** | Logic is unit tested; what Horizon OS actually reports is unknown. Manual assignment is provided as the fallback. |
| Reading Joy-Cons as Android `InputDevice`s | **IMPLEMENTED** · **NEEDS HARDWARE TEST** | Works only while JoyMerge is the focused app. Enough for calibration and the Tester. |
| Reading `/dev/input/event*` through Shizuku | **IMPLEMENTED** · **NEEDS HARDWARE TEST** | The shell uid is in the `input` group on stock Android (that is how `getevent` works). Not confirmed on Horizon OS. |
| Exclusive grab (`EVIOCGRAB`) of both Joy-Cons | **IMPLEMENTED** · **NEEDS HARDWARE TEST** | Stops the system seeing them as two separate pads. Falls back to non-exclusive automatically and says so. |
| Virtual gamepad via `/dev/uinput` | **IMPLEMENTED** · **NEEDS HARDWARE TEST** | **This is the one that may turn out to be BLOCKED.** The app probes it with a real `open(O_RDWR)` and reports the errno. |
| Fallback: privileged event injection | **IMPLEMENTED** · **WORKAROUND AVAILABLE** | Uses `InputManager.injectInputEvent` as shell. No enumerable device; events go to the focused window only. Weaker, but needs no uinput. |
| Running while another app is in the foreground | **IMPLEMENTED** · **NEEDS HARDWARE TEST** | Foreground service in the app process; the merge loop itself lives in the Shizuku process. |
| Xbox Cloud Gaming sees the merged pad | **NEEDS HARDWARE TEST** | Depends entirely on the uinput row above. |
| Shizuku running on Horizon OS | **NEEDS HARDWARE TEST** | Shizuku is a normal Android app started over adb. Nothing about it is Quest-specific, but it is unverified there. |
| Rumble / gyro / IMU passthrough | **NOT IMPLEMENTED** | Out of scope; xCloud does not need it for basic play. |

When you do test it, the Diagnostics export is exactly what is needed to update
this table.

---

## Getting the APK

### From GitHub Actions (no build tools needed)

1. Open the repository on GitHub.
2. **Actions** tab → pick the most recent successful **Android build** run.
3. Scroll to **Artifacts** → download **`JoyMergeQuest-APK`**.
4. It downloads as a zip containing **`JoyMergeQuest-debug.apk`**.

This works from an iPad: Safari can download the zip, and Files can unzip it.

### Building it yourself

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35, plus
NDK `27.2.12479018` and CMake `3.22.1`.

```bash
git clone <this repository>
cd Joycons-
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew testDebugUnitTest     # 36 unit tests, no device needed
./gradlew assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk
```

The build targets `arm64-v8a` only, which is what the Quest 2 runs.

---

## Installing on the Quest 2

You need Developer Mode on the headset:

1. Create an organisation at [developer.oculus.com](https://developer.oculus.com/).
2. In the Meta Horizon phone app: your headset → **Headset Settings** →
   **Developer Mode** → on.
3. Reboot the headset.

Then either use **SideQuest** ("Install APK from folder"), or:

```bash
adb install -r JoyMergeQuest-debug.apk
```

The app appears in the Quest library under **Unknown Sources**.

---

## Pairing the Joy-Cons

1. Quest → **Settings** → **Devices** → **Bluetooth** → **Pair new device**.
2. Hold the small **sync button** on the rail of the left Joy-Con until its
   lights run back and forth; pair it.
3. Repeat for the right Joy-Con.

The Quest will show them as two separate controllers. That is expected — making
them behave as one is this app's whole job.

Open JoyMerge and check the top of the main screen: **Joy-Con L** and
**Joy-Con R** should both read **CONNECTED**. If one says DISCONNECTED, open
**JOY-CON SLOTS** and assign it by hand; the screen lists every input device with
its VID/PID, sources and the reason detection did or did not claim it.

---

## Setting up Shizuku

Shizuku gives the app a process running as uid 2000 (shell). Without it,
JoyMerge can still calibrate and run the Gamepad Tester, but it **cannot**
create a controller other apps can see.

1. Sideload the Shizuku APK onto the headset.
2. Connect the Quest over USB (or wireless debugging) and run the start script
   Shizuku shows on its own screen — usually:

   ```bash
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```

3. In JoyMerge: **GRANT SHIZUKU PERMISSION**, allow the prompt, then
   **CONNECT PRIVILEGED SERVICE**.
4. Open **DIAGNOSTICS** → **REFRESH**. The *Privileged service* section should
   report **uid 2000**.

**Shizuku does not survive a reboot.** After every restart of the headset you
must run the adb command again. There is an in-app guide at **SET UP SHIZUKU**.

---

## Calibrating

Nothing is assumed about button codes. Horizon OS is free to report Joy-Con
buttons under codes that match no phone, so every binding comes from a button
you physically pressed while the app was watching.

Press **CALIBRATE JOY-CONS** and follow the prompts:

* Move the left stick fully left, then right; then fully up, then down.
* Same for the right stick.
* Press each D-pad direction, then A, B, X, Y, L, R, ZL, ZR, −, +, L3, R3.
* Optional: Home, and SL/SR if you tick the box.

Calibration listens on **two channels at once**:

| Channel | What it learns | Where it works |
|---|---|---|
| Android events | `KeyEvent` codes and `MotionEvent` axes | Only while JoyMerge is focused |
| Raw `/dev/input` | Kernel `EV_KEY` / `EV_ABS` codes | Everywhere, including in the background |

One press fills both. The raw channel needs Shizuku connected — the calibration
screen shows **ACTIVE / INACTIVE** for each so you know what you are getting. If
you calibrate without Shizuku you get a profile that works in the Tester and
nowhere else, and the app says so rather than pretending otherwise.

Skip anything your Joy-Cons do not report; you can bind it later from
**MAPPING**, which also lets you re-learn or clear any single control without
redoing the whole run.

### Default mapping

| Joy-Con L | → | Joy-Con R | → |
|---|---|---|---|
| Left stick | Left Stick | Right stick | Right Stick |
| D-pad buttons | D-Pad | A / B / X / Y | A / B / X / Y |
| L | LB | R | RB |
| ZL | LT | ZR | RT |
| Stick click | L3 | Stick click | R3 |
| − | Select / View | + | Start / Menu |

The virtual pad exposes: Left Stick X/Y, Right Stick X/Y, D-Pad, A, B, X, Y,
LB, RB, analog LT, analog RT, L3, R3, Start and Select.

---

## Starting the controller

1. **GAMEPAD TESTER** first. Move both sticks and press buttons on both
   Joy-Cons; you should see one merged pad responding. If the left stick moves
   the left stick and A lights up when you press A on the right Joy-Con, the
   merge is correct.
2. Back on the main screen, press **START VIRTUAL GAMEPAD**.
3. The status panel must show **Virtual Gamepad: ACTIVE** with the kernel device
   name it created (for example `uinput device "JoyMerge Virtual Gamepad"
   created as input42`). If it shows an error instead, that is the real reason —
   take it to Diagnostics.
4. A notification appears and stays while the pad is running. You can now leave
   JoyMerge and open **Xbox Cloud Gaming**.
5. In xCloud, the controller should be detected as a standard gamepad. Use its
   own controller test if you want to confirm before starting a game.

**STOP** tears everything down: the uinput device is destroyed, the exclusive
grab is released, and the foreground service goes away.

### Settings worth knowing

* **Backend** — `uinput` (preferred), `injection` (fallback), or in-app only.
* **Device identity** — the virtual pad reports a neutral virtual-bus identity by
  default. If Horizon OS ignores it, there is a generic-USB preset and an
  Xbox-compatible preset. The last one reports Microsoft's vendor and product
  ids; it is opt-in and labelled as the spoofing that it is.
* **Exclusive grab** — on by default. With it, the system stops seeing the two
  Joy-Cons separately. Turn it off if the grab fails or you want the raw
  controllers visible too.
* **Mirror triggers onto GAS/BRAKE** — publishes LT/RT on both `ABS_Z`/`ABS_RZ`
  and `ABS_BRAKE`/`ABS_GAS`, since titles disagree about which pair to read.

---

## Collecting diagnostics

**DIAGNOSTICS** → **REFRESH** collects, and reports the measured result of:

* Android release, Horizon OS/VrOS version, ABIs, app uid
* Shizuku state, version, server uid
* Privileged process uid, euid, supplementary groups, SELinux context, kernel
* `/dev/input` existence and permissions, from the app **and** from the shell process
* `/dev/uinput` existence, permissions, and the outcome of a real `open(O_RDWR)`
* Every `/dev/input/event*` node: name, bus, VID/PID, key codes, ABS axes with
  ranges, and why it could not be opened if it could not
* Every Android `InputDevice`: id, VID/PID, sources, axes, key codes, and the
  detection verdict with its reason
* Virtual gamepad state, including the sysfs name when one exists
* The most recent events received from each Joy-Con

**COPY LOG** puts the whole report on the clipboard. **EXPORT LOG** writes it to
`/sdcard/Android/data/com.joymerge.quest/files/joymerge-diagnostics-<timestamp>.txt`,
which you can pull with `adb pull` without any storage permission, and offers a
share sheet.

---

## Architecture

```
app/src/main/
├── cpp/joymerge.c                 native evdev + uinput ioctls (the only C)
├── java/com/joymerge/quest/
│   ├── core/                      pure Kotlin, no Android — unit tested
│   │   ├── GamepadModel.kt        axes, buttons, bindings, profiles
│   │   ├── GamepadMerger.kt       two signal streams -> one pad state
│   │   ├── Calibration.kt         step list + per-domain capture engine
│   │   ├── JoyConDetector.kt      VID/PID first, names second
│   │   └── ProfileCodec.kt        readable text format for profiles
│   ├── nativebridge/              JNI declarations + Linux input constants
│   ├── privileged/                runs as shell uid via Shizuku
│   │   ├── JoyMergeUserService.kt owns the whole hot path
│   │   ├── Evdev.kt               probing + one reader thread per Joy-Con
│   │   ├── UinputGamepad.kt       the merged device
│   │   ├── InputInjector.kt       injection fallback
│   │   └── PrivilegedClient.kt    app-side handle on the above
│   ├── gamepad/                   VirtualGamepadBackend + 3 implementations
│   ├── input/                     Android InputDevice enumeration, foreground path
│   ├── calibration/               drives both capture engines together
│   ├── service/                   foreground service
│   ├── diagnostics/               the report
│   └── ui/                        Compose screens
└── aidl/                          the app <-> privileged process interface
```

Two design decisions worth calling out:

**The merge loop lives in the privileged process.** Reading `/dev/input` and
writing `/dev/uinput` both need shell privileges, so the Shizuku process does
the whole job — read, merge, publish — and the app process only sends
configuration down and receives throttled state for display. That is what makes
backgrounding JoyMerge free: the hot path is not in the app at all.

**Backends are an interface, not an assumption.** `VirtualGamepadBackend` has
three implementations (`UInputBackend`, `PrivilegedInjectionBackend`,
`LoopbackBackend`) and is selectable at runtime. If the Quest turns out to
forbid uinput, a fourth can be added without touching the merge engine, the
calibration, or the UI.

---

## Known limitations

* **Shizuku must be restarted after every headset reboot.** That is Shizuku's
  design, not something this app can work around.
* **The injection fallback is genuinely weaker.** Injected events go to the
  focused window; no device appears in `InputDevice.getDeviceIds()`, and an app
  that filters by device id may ignore them. It exists so there is *something*
  if uinput is blocked, not as an equal alternative.
* **Without Shizuku, nothing leaves the app.** Calibration and the Gamepad
  Tester work; other apps see nothing. The UI states this rather than showing a
  green light.
* **No rumble, gyro or IMU.** Only buttons, sticks and triggers are merged.
* **Bluetooth range and latency are the Joy-Cons' own.** JoyMerge adds one
  kernel round trip, not a network hop, but it cannot improve a flaky link.

## Troubleshooting

| Symptom | Where to look |
|---|---|
| Joy-Con shows DISCONNECTED | **JOY-CON SLOTS** — assign it by hand; the detection reason is printed for every device. |
| `Shizuku is not ready` | **SET UP SHIZUKU**; re-run the adb start command after a reboot. |
| `UINPUT BLOCKED: Permission denied` | uinput is refused for the shell uid on this device. Switch the backend to *Privileged event injection* in **SETTINGS**. |
| `native library not loaded in the privileged process` | Diagnostics prints every load attempt, including the `/data/local/tmp` fallback and why each failed. |
| Games see three controllers | Exclusive grab did not take. Check the *Virtual gamepad* section for the `EVIOCGRAB` result. |
| Merged pad works in the Tester but not in xCloud | The Tester can run on the foreground path. Confirm the main screen says the privileged reader is READY, not FOREGROUND ONLY. |
| Buttons mapped wrongly | **MAPPING** — re-learn individual controls without redoing calibration. |

## Licence

No licence has been chosen yet; add one before distributing builds.
