import os
import sys
import urllib.request
import urllib.parse
import json
import uuid

def escape_html(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def format_changelog(changelog, repo):
    formatted_lines = []
    for line in changelog.splitlines():
        if line.strip().startswith('* **'):
            try:
                # Format: * **Title** (hash) -> - <b>Title</b> (hash-link)
                parts = line.split('**')
                title = parts[1]
                hash_part = parts[2].strip()  # e.g., "(a1b2c3d)"
                short_hash = hash_part.strip('()')
                commit_url = f"https://github.com/{repo}/commit/{short_hash}"
                formatted_line = f"- <b>{escape_html(title)}</b> (<a href=\"{commit_url}\">{short_hash}</a>)"
                formatted_lines.append(formatted_line)
            except Exception:
                formatted_lines.append(escape_html(line))
        elif line.strip().startswith('* '):
            formatted_lines.append(escape_html(line).replace('* ', '- '))
        else:
            stripped = line.strip()
            if stripped:
                if stripped.startswith('- '):
                    content = stripped[2:].strip()
                elif stripped.startswith('* '):
                    content = stripped[2:].strip()
                else:
                    content = stripped
                formatted_lines.append(f"  - {escape_html(content)}")
    return "\n".join(formatted_lines)

def send_photo(token, chat_id, filepath, caption, reply_markup):
    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    
    body = []
    body.append(f"--{boundary}".encode('utf-8'))
    body.append(b'Content-Disposition: form-data; name="chat_id"')
    body.append(b'')
    body.append(str(chat_id).encode('utf-8'))
    
    body.append(f"--{boundary}".encode('utf-8'))
    body.append(b'Content-Disposition: form-data; name="parse_mode"')
    body.append(b'')
    body.append(b'HTML')

    body.append(f"--{boundary}".encode('utf-8'))
    body.append(b'Content-Disposition: form-data; name="reply_markup"')
    body.append(b'')
    body.append(json.dumps(reply_markup).encode('utf-8'))
    
    body.append(f"--{boundary}".encode('utf-8'))
    body.append(b'Content-Disposition: form-data; name="caption"')
    body.append(b'')
    body.append(caption.encode('utf-8'))
    
    filename = os.path.basename(filepath)
    body.append(f"--{boundary}".encode('utf-8'))
    body.append(f'Content-Disposition: form-data; name="photo"; filename="{filename}"'.encode('utf-8'))
    body.append(b'Content-Type: image/png')
    body.append(b'')
    with open(filepath, 'rb') as f:
        body.append(f.read())
        
    body.append(f"--{boundary}--".encode('utf-8'))
    body.append(b'')
    
    payload = b'\r\n'.join(body)
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendPhoto",
        data=payload,
        headers=headers
    )
    with urllib.request.urlopen(req) as response:
        return response.read()

def send_message(token, chat_id, message, reply_markup):
    data = {
        "chat_id": chat_id,
        "text": message,
        "parse_mode": "HTML",
        "reply_markup": reply_markup,
        "disable_web_page_preview": True
    }
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=json.dumps(data).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as response:
        return response.read()

def send_media_group(token, chat_id, filepaths):
    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    
    body = []
    body.append(f"--{boundary}".encode('utf-8'))
    body.append(b'Content-Disposition: form-data; name="chat_id"')
    body.append(b'')
    body.append(str(chat_id).encode('utf-8'))
    
    media_list = []
    for i, filepath in enumerate(filepaths):
        attach_name = f"doc_{i}"
        media_list.append({
            "type": "document",
            "media": f"attach://{attach_name}"
        })
        
    body.append(f"--{boundary}".encode('utf-8'))
    body.append(b'Content-Disposition: form-data; name="media"')
    body.append(b'')
    body.append(json.dumps(media_list).encode('utf-8'))
    
    for i, filepath in enumerate(filepaths):
        attach_name = f"doc_{i}"
        filename = os.path.basename(filepath)
        body.append(f"--{boundary}".encode('utf-8'))
        body.append(f'Content-Disposition: form-data; name="{attach_name}"; filename="{filename}"'.encode('utf-8'))
        body.append(b'Content-Type: application/octet-stream')
        body.append(b'')
        with open(filepath, 'rb') as f:
            body.append(f.read())
            
    body.append(f"--{boundary}--".encode('utf-8'))
    body.append(b'')
    
    payload = b'\r\n'.join(body)
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMediaGroup",
        data=payload,
        headers=headers
    )
    with urllib.request.urlopen(req) as response:
        return response.read()

def main():
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat_id = os.environ.get("TELEGRAM_CHAT_ID")
    version = os.environ.get("VERSION")
    commit_hash = os.environ.get("COMMIT_HASH")
    build_time = os.environ.get("BUILD_TIME")
    apk_sha = os.environ.get("APK_SHA")
    changelog = os.environ.get("CHANGELOG", "")
    repo = os.environ.get("GITHUB_REPOSITORY")
    apk_versioned_path = os.environ.get("APK_VERSIONED_PATH")
    apk_latest_path = os.environ.get("APK_LATEST_PATH")

    if not token or not chat_id:
        print("TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is missing")
        sys.exit(1)

    changelog_html = format_changelog(changelog, repo)

    message = (
        f"<b>New update available (v{version})</b>\n\n"
        f"📦 <b>Build Information:</b>\n"
        f"- <b>Version:</b> <code>v{version}</code>\n"
        f"- <b>Commit:</b> <a href=\"https://github.com/{repo}/commit/{commit_hash}\">{commit_hash[:7]}</a>\n"
        f"- <b>Build Time:</b> <code>{build_time}</code>\n"
        f"- <b>Android:</b> <code>9.0+</code>\n"
        f"- <b>SHA-256:</b> <code>{apk_sha}</code>\n\n"
        f"📝 <b>Changelog:</b>\n"
        f"{changelog_html}"
    )

    reply_markup = {
        "inline_keyboard": [
            [
                {"text": "Direct", "url": f"https://github.com/{repo}/releases/latest/download/CQ.apk"},
                {"text": "Versioned", "url": f"https://github.com/{repo}/releases/download/v{version}/CQ-v{version}.apk"}
            ]
        ]
    }

    # Upload photo using multipart/form-data
    banner_path = "update.png"
    if os.path.exists(banner_path):
        print(f"Uploading banner: {banner_path} with message caption...")
        try:
            send_photo(token, chat_id, banner_path, message, reply_markup)
            print("Successfully sent photo post.")
        except Exception as e:
            print(f"Error sending photo, falling back to text: {e}")
            send_message(token, chat_id, message, reply_markup)
    else:
        print("update.png not found, sending as text post...")
        send_message(token, chat_id, message, reply_markup)

    # Collect existing APKs
    apks_to_upload = []
    if apk_latest_path and os.path.exists(apk_latest_path):
        apks_to_upload.append(apk_latest_path)
    else:
        print(f"Latest APK not found at {apk_latest_path}")

    if apk_versioned_path and os.path.exists(apk_versioned_path):
        apks_to_upload.append(apk_versioned_path)
    else:
        print(f"Versioned APK not found at {apk_versioned_path}")

    if apks_to_upload:
        print(f"Uploading {len(apks_to_upload)} APK(s) combined...")
        try:
            send_media_group(token, chat_id, apks_to_upload)
            print("Successfully sent combined media group.")
        except Exception as e:
            print(f"Error sending media group: {e}")

if __name__ == "__main__":
    main()
