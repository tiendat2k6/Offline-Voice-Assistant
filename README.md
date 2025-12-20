# 🎙️ Jarvis Assistant - Offline AI Voice Control

![Android](https://img.shields.io/badge/Android-10%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple.svg)
![Vosk](https://img.shields.io/badge/Vosk-Offline-orange.svg)
![Picovoice](https://img.shields.io/badge/Picovoice-Porcupine-blue.svg)

An advanced, privacy-focused Android Assistant that runs **completely offline**. It combines **Picovoice Porcupine** for wake-word detection and **Vosk** for command recognition.

Designed to be lightweight, battery-efficient, and optimized for **Xiaomi/MIUI** devices (Redmi 9T tested).

## 🚀 Key Features

### 🧠 Dual Wake Words (Porcupine)
* **"Jarvis":** Wakes up the assistant to listen for commands.
* **"Terminator":** Instantly locks the screen (no further commands needed).

### 🗣️ Offline Command Recognition (Vosk)
Powered by a pre-loaded Grammar-based model for 100% accuracy on specific commands, even in noisy environments.

### ⚡ Smart Commands
* **Flashlight Control:**
    * Say **"Flash on"** / **"Light on"**: Turns on the flashlight. **Auto-off after 60 seconds** to save battery/prevent overheating.
    * Say **"Flash off"**: Turns it off immediately.
* **Relax Mode:**
    * Say **"Relax"**: Starts a **10-minute timer**.
    * After 10 minutes, wakes you up using the **Alarm Stream** (loud sound even if media volume is muted).
* **Google Assistant:** Say **"Open Google"** to launch the native assistant.
* **Tools:**
    * **"Time"**: Reads current time.
    * **"Count down"**: Counts down from 10.
    * **"Cancel"**: Stops running timers (Flash/Relax).

### 🔋 Optimized Performance
* **Foreground Notification:** Pin notification to Status Bar with a **Play/Pause button** to easily disable the mic when not needed.
* **No Overlay Permission:** Uses **Accessibility Service** for system actions, removing the need for "Display over other apps".

## 🛠️ Tech Stack

* **Language:** Kotlin
* **Wake Word Engine:** Picovoice Porcupine (Lightweight, High sensitivity).
* **Speech-to-Text:** Vosk Android (Offline English Small Model).
* **Core Components:**
    * `AccessibilityService`: For global actions (Lock screen, Launch App).
    * `ForegroundService`: To keep the app alive.
    * `TextToSpeech (TTS)`: For voice feedback.

## ⚙️ Setup & Installation

### 1. Clone & Key
Clone the repository and get your AccessKey from [Picovoice Console](https://console.picovoice.ai/).
Update `AssistantService.kt`:
```kotlin
private val accessKey = "YOUR_PICOVOICE_KEY_HERE"
```

### 2. Download Offline Model (Crucial!)
Since this app runs offline, you must manually place the Vosk model on your phone.
1.  Download **`vosk-model-small-en-us-0.15`** from [Vosk Models](https://alphacephei.com/vosk/models).
2.  Extract the folder.
3.  Rename the folder to `model-en`.
4.  Copy it to your phone's internal storage root:
    > Path: `/sdcard/model-en`

### 3. Build & Permissions
Install the app. You must grant the following permissions manually:
1.  **Microphone:** Allow recording audio.
2.  **Accessibility Service:** Go to Settings -> Accessibility -> Downloaded Apps -> **Jarvis Assistant** -> **ON**.
3.  **Notifications:** Allow to see the status bar controller.

## 📝 Command List (Cheat Sheet)

| You Say... | Action |
| :--- | :--- |
| **"Jarvis"** | Wakes up (Beeps/Listens) |
| **"Terminator"** | Locks screen immediately |
| **"Flash / Light on"** | Flashlight ON (Auto-off 60s) |
| **"Flash / Light off"** | Flashlight OFF |
| **"Relax"** | 10-minute nap timer (Alarm wake-up) |
| **"Open Google"** | Opens Google Assistant |
| **"Time / Hour"** | Reads current time |
| **"Cancel / Stop"** | Cancels timers & Listening |

## 🔧 Troubleshooting (Xiaomi/MIUI)

To prevent the system from killing the background service:
1.  **Lock the App:** Recent Apps -> Long press App -> Tap 🔒.
2.  **Battery Saver:** App Info -> Battery Saver -> **No restrictions**.
3.  **Autostart:** App Info -> Enable **Autostart**.

## 📄 License

[MIT](https://choosealicense.com/licenses/mit/)
