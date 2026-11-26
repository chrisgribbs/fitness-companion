# Fitness Companion

## 📲 [Download the app (FitnessCompanion.apk)](https://github.com/chrisgribbs/fitness-companion/releases/latest/download/FitnessCompanion.apk)

**On your phone:** tap the link above →
open the downloaded file → allow installs from this source if asked → **Install**.
It always gets the newest build and installs over the old one, keeping your data.
All versions: [Releases](https://github.com/chrisgribbs/fitness-companion/releases).
Once installed, the app checks for new builds itself and shows an **Update available** banner.

---

## About this project

A tiny, read-only Android app that answers one question: **what can my own Fitbit Air /
Google Health data actually provide?** It tests two routes side by side:

1. **Health Connect** — on-phone data that the Google Health app copies across. No Google Cloud setup.
2. **Google Health API** — Google's web API for your account, including the TCX route export.

It shows a card per item (green = available and tested, amber = supported but not verified,
grey = not available, red = error) and can copy a text report. **No GPS coordinates are shown,
saved or included in the report**, and nothing is stored to disk.

You can do part 1 on its own. Part 2 needs the Google Cloud steps below.

---

## What you need

- A computer (Windows, Mac or Linux) with **Android Studio** (free).
- Your Android phone and a USB cable.
- On the phone: the **Google Health** app with Health Connect sync turned on
  (Google Health → Settings → Manage Health Connect; allow historic and background data if offered).

## Step 1 — Open and build

1. Unzip this folder and open it in Android Studio (**File → Open**). Let it finish "Gradle sync".
   If it offers to upgrade Gradle, AGP or libraries, accepting is fine.
2. To run the calculation tests: open the **Gradle** panel (elephant icon, right edge), then
   `FitnessCompanionPoC › core › Tasks › verification › test` and double-click it.
   These cover elevation smoothing, km splits and TCX parsing, using clearly fake tracks.

## Step 2 — Install on your phone (no Play Store, no developer verification needed)

1. On the phone: **Settings → About phone → tap "Build number" 7 times** to enable Developer options.
2. **Settings → System → Developer options → USB debugging: on.**
3. Plug in the phone, accept the "Allow USB debugging?" prompt, choose the phone in Android Studio's
   device menu and press **Run ▶**.

Installs from Android Studio go over ADB, which Google has said stays exempt from the new
Android developer-verification rules.

## Step 3 — Health Connect test

1. In the app tap **Grant Health Connect permissions** and allow what you're comfortable with.
   For routes, choose **Always allow** if offered; otherwise you'll be asked per run.
2. Tap **Run Health Connect test**.
3. If "GPS route coordinates" says consent is needed, tap **Allow route for latest session**.

## Step 4 — Google Health API test (optional, ~15 minutes, once)

You'll type everything yourself in your own browser; never send anyone a password or key.
No client secret goes in the app — Google recognises it by package name + signing fingerprint.

1. Go to <https://console.cloud.google.com>, sign in with **the same Google account your Fitbit uses**,
   and create a project (e.g. "fitness-personal").
2. **APIs & Services → Library →** search **Google Health API** → **Enable**.
3. **Google Auth Platform → Branding**: app name "Fitness PoC", your email as support and developer contact.
4. **Audience**: User type **External**, leave Publishing status **Testing**,
   and under **Test users** add your own Gmail address.
5. **Data Access → Add or remove scopes** and add these four (read-only):
   - `.../auth/googlehealth.activity_and_fitness.readonly`
   - `.../auth/googlehealth.health_metrics_and_measurements.readonly`
   - `.../auth/googlehealth.sleep.readonly`
   - `.../auth/googlehealth.location.readonly`
6. The app's signing fingerprint (SHA-1) is fixed:
   `69:CE:07:EE:12:92:C7:22:4D:7E:1F:44:AB:D7:5E:36:28:70:71:83`
   (from `app/debug.keystore`; a fingerprint isn't secret).
7. **Clients → Create client → Android**: package name `com.fitcompanion.poc`, paste the SHA-1. Save.
   (No secret is created for Android clients; there is nothing to copy into the app.)
8. Back in the app, tap **Sign in with Google and run API test**. You'll see an
   "unverified app" warning — expected for your own private project. Continue, tick the boxes, allow.

## Step 4½ — Challenges and AI (new tabs)

The app opens on **Challenges**. Tap **Refresh** to read your runs and walks from Health Connect, or flip
**Demo data** on to try the screens with made-up activities (a yellow DEMO DATA banner shows whenever
they're in use).

- **Everest:** New challenge → pick runs/walks → *Start the Everest climb*. The mountain shows your total
  climb, with this week's part highlighted. Climb comes from Google Health's own "elevation gained"
  per activity; activities without it are listed as missing, not counted as zero.
- **Your own challenge:** describe it ("walk the coast from Brisbane to Coolangatta") → *Design it with AI*.
  Review the proposal, tap **Check places** (each waypoint is looked up on OpenStreetMap; anything the AI
  misplaced by more than 20 km is moved to the real place), then **Save**. The app measures the route
  itself — it doesn't trust the AI's distance.

### OpenRouter key (you enter it yourself)

1. Sign in at <https://openrouter.ai>, add a little credit, and create a key under **Keys**. Setting a
   monthly limit on the key is a good idea.
2. In the app's **AI** tab, paste the key and tap **Save**. It's encrypted with a key held in the Android
   Keystore and never leaves the phone except in requests to OpenRouter.
3. The model box starts with `openai/gpt-5.2`; you can type any model id listed on openrouter.ai/models.

What's sent, and when:
- **Designing a challenge:** only your description.
- **Asking a question:** your question plus a "facts sheet" of totals the app calculated (weekly
  distances, climb, daily steps/resting HR/HRV/sleep for 14 days, 90-day averages, challenge progress).
  You see the exact text first and must tap **Send**. No GPS routes or raw heart-rate data are ever sent.
- **Checking places:** only place names, to OpenStreetMap's Nominatim service, one per second, with
  results cached. This follows the
  [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/): it's never used in
  the background or in bulk.

## Step 5 — Send back the results

Tap **Copy report** (or **Share report…**), read it, and paste it into the chat.
It contains counts, dates and summary values, but no coordinates.

## Disconnecting

- Health Connect: **Settings → Security & privacy → Privacy → Health Connect → App permissions →
  Fitness PoC → Remove all**. (Menu path varies slightly by phone.)
- Google: <https://myaccount.google.com/connections> → Fitness PoC → remove access.
- Uninstalling the app removes everything it had (it stores nothing).

## Things the test will tell us (and you can watch for)

- Whether Google Health writes **HRV**, **resting heart rate**, **sleep** and **routes with altitude**
  into Health Connect for a Fitbit Air (public sources disagree; this is the point of testing).
- How far back data goes in each source.
- Whether TCX trackpoints include altitude and heart rate.
- With the Google project in "Testing", sign-in may expire after about **7 days**. If the API test
  stops working a week from now with a sign-in error, that confirms it.

## If something doesn't build

The Kotlin calculation module was compiled in the build environment; the Android app code could
not be compiled there (no Android SDK available). If Android Studio flags an error, paste the
message into the chat — it will most likely be a small library-version difference.
