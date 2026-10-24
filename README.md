# Digital Witness · Terenski snimač

**Android aplikacija za beleženje događaja i čuvanje kopija snimaka na odredištu koje sami birate.**

Pokrenite snimanje, zaključajte telefon i nastavite da snimate. Aplikacija čuva video u kratkim segmentima i, kada su odredište i veza podešeni, šalje završene delove dok snimanje traje. Uz snimke nastaju podaci koji omogućavaju naknadnu proveru njihovog integriteta.

Ovo je **pojedinačno izdanje**: sami podešavate aplikaciju i svoje mesto za čuvanje kopija. Nisu potrebni organizator, timski nalog ni dodeljeno korisničko ime.

[Preuzmi APK](https://github.com/mr-rogers-rs/digital-witness/releases/latest) · [Uputstvo za korišćenje](docs/USAGE.md) · [Izgradnja iz izvornog koda](docs/BUILD.md) · [English overview](#english-overview)

## Preuzimanje i instalacija

Potrebni su **Android 8.0 ili noviji**, kamera i dovoljno slobodnog prostora.

1. Otvorite [izdanja aplikacije](https://github.com/mr-rogers-rs/digital-witness/releases).
2. Za izdanje **0.4.7**, u odeljku **Assets** preuzmite `digital-witness-0.4.7.apk`. Datoteke „Source code” služe za izgradnju aplikacije, a ne za instalaciju na telefon.
3. Otvorite APK na telefonu. Ako Android zatraži dozvolu za instaliranje iz tog izvora, odobrite je za pregledač ili upravljač datotekama koji koristite.
4. Pokrenite aplikaciju **Terenski snimač** i prođite kroz početno podešavanje. Dostupni su srpski na latinici i engleski jezik.

Uz APK je dostupan `digital-witness-0.4.7.apk.sha256`. Pre instalacije možete proveriti da li se SHA-256 preuzete datoteke poklapa sa objavljenom vrednošću. Na Windowsu:

```powershell
Get-FileHash .\digital-witness-0.4.7.apk -Algorithm SHA256
```

Kontrolna suma pomaže da otkrijete oštećenu ili različitu datoteku. Sama po sebi ne potvrđuje identitet izdavača.

Slanje koristi do dve istovremene datoteke kada izmerena brzina to opravdava. Pri greškama ili trajnom usporavanju automatski se vraća na jednu. Ova promena ne menja podešavanja kvaliteta videa. Brzina zavisi od veze i izabranog servisa; preko birača foldera proverava se sadržaj dostupan posredničkoj aplikaciji, a dolazak u oblak proverite na samom servisu.

## Prvi snimak: podesi → snimaj → zaključaj → proveri

1. **Podesite zaštitu, dozvole i rad u pozadini.** Izaberite lični PIN aplikacije. Dozvolite pristup kameri; pristup mikrofonu je potreban samo ako želite zvuk. Pre početka snimanja označite ili uklonite oznaku u polju **Snimaj zvuk** ispod prikaza kamere; ista opcija je dostupna i u Podešavanjima. U koraku **Snimanje sa zaključanim ekranom** proverite ograničenja baterije; aplikacija nudi otvaranje potrebnih podešavanja pre prvog snimanja.
2. **Izaberite gde se čuvaju kopije.** Dodirnite **Izaberi folder** (preporučeno). U biraču datoteka otvorite meni gore levo (☰) i izaberite podržani servis u oblaku ili folder na telefonu. Google Drive, Dropbox i drugi servisi pojavljuju se samo ako njihova instalirana aplikacija omogućava pristup folderima. Nextcloud je dodatna mogućnost. Možete nastaviti i bez podešenog slanja, ali tada morate pratiti slobodan prostor na telefonu.
3. **Izaberite način povezivanja.** Podrazumevano je izabrana **Bilo koja veza**: Wi-Fi ili mobilni internet, bez obaveznog VPN-a. U početnom podešavanju možete izabrati da slanje čeka VPN ili Tor. Za udaljene servere koristite HTTPS.
4. **Snimajte.** Proverite kadar u prikazu kamere, po potrebi uključite blic i dodirnite **Snimaj**. Snimanje započnite dok je aplikacija otvorena; zatim možete zaključati telefon.
5. **Zaustavite i proverite.** Otključajte telefon, dodirnite **Zaustavi snimanje** u aplikaciji ili u njenom obaveštenju i proverite da li je red za slanje ispražnjen. Na odredištu preuzmite i pustite rani i završni deo snimka, sa zvukom ako ste ga uključili.

Pre važne upotrebe napravite probni snimak na svom telefonu, sa zaključanim ekranom i vezom koju ćete stvarno koristiti. Podešavanja štednje baterije, temperatura i slobodan prostor mogu uticati na snimanje.

## Šta aplikacija omogućava

- Prikaz kamere preko cele širine ekrana pre i tokom snimanja, bez razvlačenja ili odsecanja slike.
- Dugme za početak i zaustavljanje snimanja ostaje vidljivo pri pomeranju ekrana.
- Podešavanja su dostupna preko zupčanika gore desno, a blic preko oivičene ikone gore levo u prikazu kamere. Precrtana ikona znači da je blic isključen.
- Polje **Snimaj zvuk** nalazi se ispod prikaza kamere i ostaje vidljivo pri pomeranju ekrana.
- Pokazatelj napretka pri proveri PIN-a, pokretanju i čuvanju snimka.
- Zeleno dugme za početak, crveno za zaustavljanje i mogućnost zaustavljanja iz obaveštenja.
- Blic pre i tokom snimanja, ako ga kamera podržava.
- Video sa zvukom ili bez korišćenja mikrofona.
- Snimanje nakon zaključavanja ekrana, uz Androidovo obaveštenje o radu aplikacije.
- Čuvanje u kratkim segmentima i slanje završenih delova tokom snimanja.
- Red za slanje koji čuva nezavršene zadatke kada veza nije dostupna ili izabrana mrežna pravila blokiraju slanje.
- Automatsko prilagođavanje protoka videa kada slanje duže zaostaje.
- Šifrovanje završenih privatnih snimaka na uređaju i izbor načina izvoza.
- Prateće datoteke za proveru integriteta snimaka pomoću alata iz ovog repozitorijuma.

Ako oporavak ranijeg prekinutog snimka ne uspe, njegovi podaci ostaju na telefonu i možete započeti novo snimanje. Aplikacija obrađuje oporavak i novo snimanje odvojeno, sa isključivim pristupom zapisima.

Snimanje stvara **više video-datoteka**, a ne jedan automatski spojen video. Slanje počinje po završetku pojedinačnog segmenta; ovo nije prenos slike uživo.

## Gde se čuvaju kopije

| Odredište | Šta treba podesiti |
| --- | --- |
| **Izaberi folder — preporučeno** | Folder na telefonu ili u podržanom servisu u oblaku, izabran pomoću Androidovog birača datoteka. |
| **Nextcloud — dodatna mogućnost** | HTTPS adresa sopstvenog servera ili naloga, korisničko ime i lozinka za aplikaciju. |
| **WebDAV** | Sopstvena HTTPS adresa i pristupni podaci, kroz podešavanja. |
| **Google Drive ili Dropbox, ručno podešavanje** | Sopstveni pristupni podaci i konfiguracija provajdera koju traže podešavanja. Ovo izdanje nema ugrađen zajednički nalog ili spreman Google OAuth projekat. |

Upis u fasciklu koju prikazuje Android nije dovoljan dokaz da je datoteka stigla u oblak. Proverite je i u samom udaljenom servisu. Detalji su u [uputstvu za odredišta i slanje](docs/USAGE.md).

### Obične ili šifrovane kopije

**Pošalji nešifrovane snimke** je podrazumevani izbor na novoj instalaciji. Šifrovanje za primaoca možete uključiti u **Podešavanja → Napredna podešavanja → Šifrovanje datoteka**:

- **Obične datoteke** mogu se otvoriti u odgovarajućem video-plejeru. HTTPS štiti prenos do servera; servis i osobe sa pristupom odredištu mogu čitati datoteke.
- **Šifrovanje za primaoca** zahteva njegov RSA javni ključ i šalje `.jwe` datoteke. Primalac ih dešifruje svojim privatnim ključem, pa ih tek onda otvara kao video. Uputstvo i alat su opisani u [USAGE.md](docs/USAGE.md).

Zaštićeno čuvanje na telefonu je odvojeno od ovog izbora: završene privatne datoteke šifruju se ključevima iz Android Keystore-a. Tokom snimanja i obrade postoje privremene nešifrovane datoteke.

## Kvalitet slike i brzina slanja

Početni ciljni protok videa je **2 Mb/s**. Kada red za slanje duže raste, automatsko prilagođavanje može ga smanjiti na **1,75 Mb/s**, zatim na **1,5 Mb/s**, i vratiti ga kada slanje sustigne snimanje. Ova opcija zavisi i od podrške telefona.

Rezolucija i broj kadrova ostaju isti, ali niži protok može smanjiti vidljive detalje, naročito pri kretanju. Za video pri 2 Mb/s računajte približno **15 MB u minutu**, uz dodatni prostor za zvuk i prateće podatke. Na sporoj vezi red može rasti i pri najnižem protoku; pratite status slanja i slobodan prostor.

## Privatnost i granice zaštite

Podrazumevan je **režim za dokaze**, koji zadržava dodatne podatke o uređaju i okruženju i potpisuje zapise za proveru. Anonimni režim dostupan je u **Podešavanja → Napredna podešavanja → Režim snimanja** i smanjuje određene metapodatke. **Ne skriva lica, glasove, sadržaj kadra, nalog za čuvanje kopija ili vašu mrežnu adresu.** Izaberite režim prema nameni snimanja. Sačuvani izbori postojećih korisnika ostaju na snazi.

Provera integriteta može pomoći da se utvrde promene i veze između segmenata. Ne potvrđuje istinitost prikazanog događaja, identitet snimatelja ili prihvatljivost snimka na sudu.

PIN aplikacije ne zamenjuje zaključavanje i zaštitu telefona. Opcije brisanja i kod za slučaj prinude mogu uništiti lokalne podatke i ključeve; ne uklanjaju već poslate kopije i ne garantuju fizičko brisanje svih podataka iz memorije.

Detaljnije: [bezbednost i ograničenja](docs/SECURITY.md) i [provera rada pre upotrebe](docs/VALIDATION.md).

## Za programere

Izvorni kod obuhvata Android aplikaciju, Gradle wrapper, alate za proveru i automatizovane testove. Privatni ključevi za potpisivanje, pristupni podaci i snimci nisu deo repozitorijuma.

Potrebni su **JDK 17**, **Android SDK Platform 34** i **Build-Tools 34.0.0**. Podesite `JAVA_HOME` i putanju do svog Android SDK-a prema [BUILD.md](docs/BUILD.md). Iz korena repozitorijuma, u PowerShellu:

```powershell
Set-Location android-app
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

Debug APK nastaje u `android-app/app/build/outputs/apk/debug/app-debug.apk`, računajući od korena repozitorijuma. Za javnu distribuciju napravite release izdanje i potpišite ga sopstvenim ključem prema [uputstvu za izgradnju](docs/BUILD.md). Bez konfiguracije potpisivanja release APK ostaje nepotpisan.

Za ažuriranje postojeće instalacije potrebni su isti identifikator aplikacije i isti ključ za potpisivanje. Ne uklanjajte aplikaciju sa neposlatim snimcima radi rešavanja razlike u potpisima.

| Dokument | Sadržaj |
| --- | --- |
| [USAGE.md](docs/USAGE.md) | Prvo podešavanje, mreža, odredišta i šifrovanje kopija. |
| [BUILD.md](docs/BUILD.md) | Izgradnja, potpisivanje i instalacija. |
| [VALIDATION.md](docs/VALIDATION.md) | Automatizovani testovi, provera snimanja i provera integriteta. |
| [SECURITY.md](docs/SECURITY.md) | Model zaštite i ograničenja. |
| [HANDOFF.md](HANDOFF.md) | Sadržaj izvornog paketa i provera njegovog manifesta. |

Identifikator aplikacije: `com.fieldnotes.recorder` · Verzija: **0.4.7** · Kod verzije: **11** · Minimalni Android API: **26**.

## Prijava problema

Otvorite [GitHub issue](https://github.com/mr-rogers-rs/digital-witness/issues) i navedite model telefona, verziju Androida, verziju aplikacije, korake za ponavljanje i da li je ekran bio zaključan. Za problem sa slanjem navedite vrstu veze i odredišta.

Koristite bezazlen probni materijal. Iz javne prijave uklonite PIN-ove, tokene, lozinke, privatne adrese servera i podatke osoba koje se vide ili čuju na snimku.

## English overview

Digital Witness is an Android recorder with a live camera preview, torch controls, optional audio, recording after screen lock, and uploads of completed video segments to user-configured storage. The Serbian app name is **Terenski snimač**. English and Serbian Latin interfaces are available.

This is the individual edition. It requires no organizer or team roster. The recommended folder picker supports on-device folders and cloud apps that expose writable folders through Android. Nextcloud remains optional; manual cloud integrations require your own provider configuration. Fresh installs use evidence mode, ordinary playable uploads and any available connection. Encryption and anonymous mode are available in advanced settings; existing saved choices are retained. Battery settings are offered during setup rather than when recording starts. Adaptive video bitrate starts at 2 Mb/s and can reduce to 1.5 Mb/s under sustained upload backlog.

Download the signed APK from [Releases](https://github.com/mr-rogers-rs/digital-witness/releases). Android 8.0 or newer is required. Test recording, screen locking, uploads and downloaded playback on your device before relying on it. Anonymous mode reduces selected metadata; it does not guarantee anonymity. Integrity proofs do not establish that a recorded scene is true.

English documentation: [usage](docs/USAGE.md), [building](docs/BUILD.md), [validation](docs/VALIDATION.md), and [security](docs/SECURITY.md).

## Licenca / License

Copyright: **Digital Witness Contributors**. Projekat se distribuira pod licencom **AGPL-3.0-only**. Pogledajte [LICENSE](LICENSE) i [NOTICE](NOTICE) za pune uslove i sačuvane atribucije. Zavisnosti zadržavaju sopstvene licence.
