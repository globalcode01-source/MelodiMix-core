# Melodi Mix core

The open-source audio core of [Melodi Mix](https://globalcode01-source.github.io/melodi-mix/), a 4-deck DJ mixer and
music player for Android. Licensed under the GNU GPL v3 (see `LICENSE`).

## What is here
Pure Kotlin, no Android dependency, with unit tests (`./gradlew test`):
- `dj/`: track analysis (BPM, key, energy) with an FFT, harmonic mixing (Camelot), beat sync maths, ID3 reading
- `download/`: chunk maps and HTTP range maths for resumable parallel downloads, ID3 writing
- `record/`: PCM helpers and the recording bus that lines up decks for mix recording
- `remix/`: mix maths and a small sample synthesizer

This repository is a part of the app, not the whole app: the user interface, the players, effects, stem separation,
café/party mode, ads and payments are not included.

## Türkçe
Melodi Mix'in açık kaynak ses çekirdeği (GPLv3): parça analizi (BPM, ton, enerji), harmonik eşleme, senkron
hesapları, ID3, devam ettirilebilir indirme ve mix kaydı hesapları. Saf Kotlin, Android bağımlılığı yok, testli.
Arayüz, oynatıcılar, efektler, stem ayırma, kafe/parti modu, reklam ve ödeme bu depoda yoktur.

Contact: globalcode01@gmail.com
