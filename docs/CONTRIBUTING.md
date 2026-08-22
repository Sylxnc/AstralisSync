# Contributing & Release Process

## Branch model

```
main      ← nur PRs aus staging (geschützt, kein Direktpush)
  │
staging   ← Feature-Branches münden hier; baut automatisch "Latest"-Release
  │
wip/<thema>
          ← Arbeitsbranches für ein Thema (z.B. wip/invsee-cache)
```

* `main` ist **immer stabil** und wird ausschließlich per Pull Request aus `staging` aktualisiert.
* `staging` erhält die laufenden Merge-Produkte; jeder Push erzeugt automatisch einen **„Latest"** Pre-Release mit JAR.
* Arbeit passiert auf `wip/<thema>`-Branches (`wip/ec-gui`, `wip/webhook-retry`, …).

## Versionierung (SemVer)

`MAJOR.MINOR.PATCH` – Tags als `v1.2.3`.

| Änderung | Bump | Beispiel |
|---|---|---|
| Breaking: Snapshot-Format, API-Entfernung, Config-Renaming ohne Fallback | MAJOR | `v1.0.0 → v2.0.0` |
| Neues Feature (z.B. neuer Sync-Bereich, neue API-Methode) | MINOR | `v1.0.0 → v1.1.0` |
| Bugfix, Performance, Docs | PATCH | `v1.1.0 → v1.1.1` |

Vor jedem Release: Version in `pom.xml` setzen, Eintrag in `CHANGELOG.md` ergänzen.

## Release-Ablauf

```bash
# 1) Feature fertigstellen
git checkout -b wip/mein-thema staging
git commit -m "feat: ..."
gh pr create --base staging --head wip/mein-thema

# 2) Nach Merge in staging → "latest" Release entsteht automatisch

# 3) Release freigeben
git checkout main
gh pr create --base main --head staging --title "Release v1.1.0"
git tag -a v1.1.0 -m "v1.1.0" && git push origin v1.1.0
```

Der Push des Tags `v*` baut das JAR und erstellt automatisch einen GitHub **Release** mit dem Asset `AstralisSync-v1.1.0.jar` und generierten Release Notes.

## Workflows

| Workflow | Trigger | Ergebnis |
|---|---|---|
| `build.yml` | jeder Push / PR | Kompiliert, lädt JAR als Artifact hoch |
| `latest.yml` | Push auf `staging` | Aktualisiert den `latest` Pre-Release |
| `release.yml` | Tag `v*` | Erstellt echten Release mit versioniertem JAR |
