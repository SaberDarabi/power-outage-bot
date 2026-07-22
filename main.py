import os
import requests
import jdatetime
from datetime import datetime

# Environment variables retrieved from GitHub Secrets
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID")

API_URL = "https://khamooshi.maztozi.ir/api/outages"

def to_persian_digits(str_val: str) -> str:
    """Convert English digits to Persian digits."""
    english_digits = "0123456789"
    persian_digits = "۰۱۲۳۴۵۶۷۸۹"
    return str_val.translate(str.maketrans(english_digits, persian_digits))

def get_jalali_today() -> str:
    """Get current Jalali date string in YYYY/MM/DD format with Persian digits."""
    now = jdatetime.datetime.now()
    date_str = now.strftime("%Y/%m/%d")
    return to_persian_digits(date_str)

def send_telegram_alert(message: str) -> None:
    """Send formatted message via Telegram Bot API."""
    if not TELEGRAM_BOT_TOKEN or not TELEGRAM_CHAT_ID:
        raise ValueError("TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID environment variables are missing!")

    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage"
    payload = {
        "chat_id": TELEGRAM_CHAT_ID,
        "text": message,
        "parse_mode": "Markdown"
    }
    response = requests.post(url, json=payload, timeout=15)
    response.raise_for_status()

def fetch_and_notify() -> None:
    """Fetch outage data for MSR14F02 feeder and send alert."""
    jalali_date = get_jalali_today()
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Content-Type": "application/json",
        "Accept": "application/json, text/plain, */*",
        "Origin": "https://khamooshi.maztozi.ir",
        "Referer": "https://khamooshi.maztozi.ir/"
    }
    
    # Payload configured specifically for Bahari Alley (MSR14F02)
    payload = {
        "fromDate": jalali_date,
        "toDate": jalali_date,
        "city": -1,
        "pgds": "MSR14F02"
    }
    
    print(f"Requesting outage schedule for Jalali Date: {jalali_date}")
    
    response = requests.post(API_URL, json=payload, headers=headers, timeout=20)
    response.raise_for_status()
    
    data = response.json()
    
    # Parse and format the output message
    if isinstance(data, list) and len(data) > 0:
        outage_items = []
        for item in data:
            start_time = item.get("fromTime", item.get("startTime", "نامشخص"))
            end_time = item.get("toTime", item.get("endTime", "نامشخص"))
            outage_items.append(f"⏱ از ساعت **{start_time}** تا **{end_time}**")
        
        details_str = "\n".join(outage_items)
        msg = (
            f"⚡ *اطلاعیه قطعی برق امروز*\n\n"
            f"📍 **موقعیت:** ساری، بلوار خزر، خیابان ساری کنار، کوچه بهاری\n"
            f"📅 **تاریخ:** {jalali_date}\n\n"
            f"⏰ **ساعات قطعی:**\n{details_str}"
        )
    elif isinstance(data, dict) and data:
        msg = (
            f"⚡ *اطلاعیه قطعی برق امروز*\n\n"
            f"📍 **موقعیت:** ساری، بلوار خزر، خیابان ساری کنار، کوچه بهاری\n"
            f"📅 **تاریخ:** {jalali_date}\n\n"
            f"⏰ **جزئیات:**\n`{data}`"
        )
    else:
        msg = (
            f"✅ *وضعیت برق امروز*\n\n"
            f"📍 **موقعیت:** ساری، بلوار خزر، خیابان ساری کنار، کوچه بهاری\n"
            f"📅 **تاریخ:** {jalali_date}\n\n"
            f"🟢 برای امروز هیچ برنامه قطعی برقی ثبت نشده است."
        )
        
    send_telegram_alert(msg)
    print("Telegram alert sent successfully.")

if __name__ == "__main__":
    fetch_and_notify()
