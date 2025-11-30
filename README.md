# 🎙️ Jarvis Trigger - Android Wake Word Launcher

![Android](https://img.shields.io/badge/Android-10%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple.svg)
![Status](https://img.shields.io/badge/Status-Active-brightgreen.svg)

An Android application that enables hands-free activation of **Google Assistant** using a custom wake word (e.g., "Jarvis"), even when the screen is off or the app is in the background.

Designed to overcome background activity restrictions on modern Android versions (Android 10+), with specific optimizations for **Xiaomi/MIUI** devices.

## 🚀 Features

* **Always-on Listening:** Uses a Foreground Service to listen for the wake word continuously.
* **Custom Wake Word:** Integrated with [Picovoice Porcupine](https://picovoice.ai/porcupine/) for high-accuracy hotword detection ("Jarvis").
* **Background Launch:** Bypasses Android's background activity restrictions using `SYSTEM_ALERT_WINDOW` (Overlay permission).
* **Direct Integration:** Launches Google Assistant directly without intermediate UI.
* **MIUI Optimized:** Includes instructions and permission handling for Xiaomi devices (Redmi 9T, etc.).

## 🛠️ Tech Stack

* **Language:** Kotlin
* **Voice Engine:** Picovoice Porcupine (On-device detection)
* **Android Components:**
    * Foreground Service
    * Broadcast Receiver
    * Intent (Voice Command)

## ⚙️ Setup & Installation

1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/yourusername/JarvisTrigger.git](https://github.com/yourusername/JarvisTrigger.git)
    ```

2.  **Get Picovoice Access Key:**
    * Sign up at [Picovoice Console](https://console.picovoice.ai/).
    * Copy your `AccessKey`.

3.  **Configure the Key:**
    * Open `AssistantService.kt`.
    * Replace the placeholder with your key:
        ```kotlin
        private val accessKey = "YOUR_ACCESS_KEY_HERE"
        ```

4.  **Build and Run:**
    * Open in Android Studio.
    * Connect your device via ADB.
    * Run the app.

## ⚠️ Important: Permissions (Manual Setup)

Due to security restrictions on Android (especially MIUI), you must grant permissions manually after installation:

1.  **Microphone:** Allow recording audio.
2.  **Display over other apps (Overlay):** Crucial for launching Assistant from the background.
3.  **Notifications:** Required for the Foreground Service.

### 🔧 Troubleshooting for Xiaomi / Redmi (MIUI) Users

If the app stops working when you clear recent apps or turn off the screen, follow these steps:

1.  **Lock the App:** Open Recent Apps -> Long press `JarvisTrigger` -> Tap the 🔒 (Lock) icon.
2.  **Battery Saver:** Settings -> Apps -> JarvisTrigger -> Battery Saver -> Select **No restrictions**.
3.  **Background Pop-up:** Settings -> Apps -> Permissions -> Other Permissions -> JarvisTrigger -> Enable **"Display pop-up windows while running in the background"**.

## 🤝 Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## 📄 License

[MIT](https://choosealicense.com/licenses/mit/)