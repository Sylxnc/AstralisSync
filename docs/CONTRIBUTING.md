# Contributing

## Branch model

```
main          stable; receives changes only through pull requests from staging
  │
staging       integration branch; every push produces a rolling "latest" pre-release
  │
wip/<topic>   short-lived feature branches (e.g. wip/invsee-cache, wip/ec-gui)
```

- `main` is protected: direct pushes are rejected. Changes arrive exclusively via merged pull requests from `staging`.
- `staging` is also history-protected but accepts direct pushes from collaborators during integration.
- Work happens on `wip/<topic>` branches cut from `staging`.

## Versioning

Semantic versioning with `vMAJOR.MINOR.PATCH` tags.

| Change | Bump | Example |
|---|---|---|
| Breaking change: snapshot format, removed API, config key without fallback | MAJOR | `v1.0.0 → v2.0.0` |
| New feature or new API method | MINOR | `v1.0.0 → v1.1.0` |
| Bug fix, performance improvement, documentation | PATCH | `v1.1.0 → v1.1.1` |

Before releasing: set the version in `pom.xml` and add a `CHANGELOG.md` entry.

## Release flow

```bash
# 1) Finish the feature
git checkout -b wip/my-topic staging
git commit -m "feat: ..."
gh pr create --base staging --head wip/my-topic

# 2) After merging into staging, the "latest" pre-release updates automatically

# 3) Promote to a release
git checkout main && git pull
gh pr create --base main --head staging --title "Release v1.1.0"
git tag -a v1.1.0 -m "v1.1.0" && git push origin v1.1.0
```

Pushing a `v*` tag builds the jar and publishes a GitHub release containing `AstralisSync-<tag>.jar` with generated release notes.

## CI workflows

| Workflow | Trigger | Result |
|---|---|---|
| Build | any push or pull request | Compile check; jar uploaded as artifact |
| Latest Build | push to `staging` | Updates the rolling `latest` pre-release |
| Release | push of a `v*` tag | Publishes a versioned GitHub release |

## Code guidelines

- Keep `mvn clean package` green.
- All database and network I/O stays off the server thread.
- Do not modify the shade relocation rules in `pom.xml`.
- New configuration keys belong in `config.yml`, documented in `docs/CONFIGURATION.md`.
