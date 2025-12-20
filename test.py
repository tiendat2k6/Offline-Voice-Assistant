import pyttsx3
import time

engine = pyttsx3.init()
engine.setProperty('rate', 150) # Tốc độ vừa phải
engine.setProperty('pitch', 50) # Tùy engine, có thể không hỗ trợ pitch

def jarvis_say():
    print("Yes...")
    engine.say("Yes.")
    engine.runAndWait()
    
    time.sleep(0.25) # ĐÂY LÀ KHOẢNG NGHỈ BẠN MUỐN CHỈNH (0.25 giây)
    
    print("Sir.")
    engine.say("Sir.")
    engine.runAndWait()

jarvis_say()
