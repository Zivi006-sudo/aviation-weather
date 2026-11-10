# Aviation Weather

A personal Android app that puts METAR, TAF and satellite imagery for the Maldives on one screen.

**For situational awareness only.** It does not replace official company weather sources, the briefing package or the aircraft's weather radar.

## What's in this version (stage 1)

- **Airports** – live METAR and TAF, raw and decoded, with colour-coded flight category (VFR / MVFR / IFR / LIFR).
  Starts with VRMM, VRMG, VRMH, VRMK and VRMT; add any airport by ICAO code.
- **Runway wind** – headwind and crosswind for each runway end, including gusts, with a wind diagram.
- **TAF periods** – each BECMG / TEMPO / PROB / FM group decoded with its own flight category and the current period highlighted.
- **Satellite map** – EUMETSAT Meteosat infrared over the Indian Ocean, with a colour scale for cloud-top cold,
  a 1 / 2 / 3 hour timeline, play / pause, speed control, scrubbing, and each image's time in UTC and local.
- **Offline** – the last weather and images are kept on the phone.
- **Night mode** – turns the whole screen red.

## Installing

Every push builds an APK with GitHub Actions. Open **Releases → Aviation Weather (latest build)** on your phone,
download `AviationWeather.apk` and install it.

## Data sources

- METAR / TAF: Aviation Weather Center, `aviationweather.gov/api/data`
- Satellite: EUMETSAT EUMETView WMS, layer `msg_iodc:ir108`

## Planned next

Sector view with TAF at ETA, minima check, SIGMETs, rain-rate layer, METAR trend graphs, notifications,
home-screen widget, sunrise / sunset, sharing.
