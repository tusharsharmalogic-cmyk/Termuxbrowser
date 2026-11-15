# Termux Browser

Ek chhota Android browser (WebView) jo minimize hone ke baad bhi chalta rehta hai
aur Termux se control hota hai. Tumhari `script.js` (Termux AI Agent) browser ke
andar hi chalti hai, isliye "button press nahi hota / output nahi padhta" wali
problem nahi aani chahiye.

## Kaise kaam karta hai
- **WebView** ek hi process-wide object hai. Activity dikhti ho to uske andar, Home /
  Minimize par ek full-size, lagbhag invisible **overlay window** me chala jata hai
  (Android use "visible" maanta hai, isliye timers/rendering nahi ruke).
- **Foreground service** + wake lock process ko zinda rakhte hain.
- `assets/agent.js` (tumhari script, original v17.10) claude.ai / chatgpt.com /
  chat.deepseek.com / gemini.google.com par apne aap inject hoti hai.
  `GM_xmlhttpRequest` ka shim `http://localhost:5000` (tumhara `agent.py`) tak
  native bridge se jata hai. Bridge sirf in sites ke main frame ke liye allowed hai.
- `127.0.0.1:8765` par ek control server hai (token ke saath) -> Termux se
  `bctl.sh` se click/eval/load kar sakte ho.

## Build (sabse aasan: GitHub Actions)
1. Ye poora folder ek naye GitHub repo me push karo.
2. Repo -> **Actions** -> "Build APK" -> run complete hone par artifact
   `TermuxBrowser-debug-apk` download karo.
3. `app-debug.apk` install karo.

(Android Studio se bhi khul jata hai: Open -> folder -> Run. Gradle 8.7+ chahiye.)

## Pehli baar
1. App kholo, notification permission Allow karo.
2. Login karo (Claude/ChatGPT/DeepSeek). Google login WebView me block ho sakta hai,
   email/password ya OTP login use karo.
3. **Minimize** dabao:
   - pehli baar "Display over other apps" ON karne ko bolega -> ON karo, wapas aao.
   - phir battery restriction hatane ka popup -> Allow.
   - phir dobara Minimize.
4. Termux me `termux-wake-lock` aur `python agent.py` chalao
   (agent.py me `host='127.0.0.1'` rakhna).

## Termux se control
```sh
export BROWSER_TOKEN=<app ke "Token" button se copy kiya hua>
sh bctl.sh ping
sh bctl.sh url
sh bctl.sh eval 'document.title'
sh bctl.sh click 'button[aria-label="Send message"]'
sh bctl.sh show        # browser wapas samne
```

## Settings jo tweak ho sakti hain (BrowserHolder.kt)
- `OVERLAY_ALPHA` (0.01): agar background me rendering/timers ruk jayein to 0.05-0.2 try karo.
- `HOME_URL`, `ALLOWED_HOSTS`, `AGENT_PORT`.
- `assets/agent.js` badalni ho to bas file replace karke dobara build.

## Dhyan do
- Ye code maine likha hai par yahan compile/test nahi kiya (Android SDK nahi tha),
  to pehle build me chhoti compile errors aa sakti hain, error bhej dena.
- Chat sites ko automate karna unke ToS ke khilaaf ho sakta hai aur unka HTML badalne
  par selectors toot jate hain.
- Token kisi ko mat dena: token wala koi bhi app tumhare browser (aur login sessions) ko control kar sakta hai.
