# GitHub Update Mirrors

DMC keeps update discovery and installer transfer as separate routes. In **Settings → App updates**, configure **Update check mirror** and **Installer download mirror** independently. Both are empty by default, which means both requests go directly to GitHub.

The update check requests the public DMC Releases API. The installer route only accepts the expected DMC Inno Setup asset for the release version selected by the update checker. The updater still validates the asset's expected URL, exact length, and SHA-256 before staging it for installation.

## Built-in presets

| Route | Preset | Template | Notes |
| --- | --- | --- | --- |
| Check | GitHub direct | — | Default route |
| Check | GH-Proxy (.org) | `https://gh-proxy.org/{url}` | GH-Proxy's documented current domain; API and release assets are documented as supported |
| Check | GH-Proxy compatibility (.com) | `https://gh-proxy.com/{url}` | Kept as a separately selectable compatibility hostname |
| Download | GitHub direct | — | Default route |
| Download | GHFast | `https://ghfast.top/{url}` | File-download route |
| Download | GHProxy.net | `https://ghproxy.net/{url}` | File-download route |
| Download | GH-Proxy (.org) | `https://gh-proxy.org/{url}` | Documented release asset route |
| Download | GH-Proxy (.com) | `https://gh-proxy.com/{url}` | Compatibility hostname |

Mirror capabilities can change. The list at [ghproxy.link](https://ghproxy.link/) is a mirror directory, and the [GH-Proxy guide](https://gh-proxy.com/docs/github-accelerator) describes support for both GitHub REST API requests and release assets. On 2026-10-17, direct probes from the development machine returned API HTTP 403 from GHFast and GHProxy.net while their installer asset endpoints responded; GH-Proxy `.org` and `.com` returned API HTTP 200 and installer responses. These are point-in-time observations, not availability guarantees.

## Custom template

Enter an HTTPS URL template containing exactly one `{url}` token, for example:

```text
https://mirror.example/{url}
```

Leave the field blank to use GitHub directly. Templates with non-HTTPS schemes, user information, query parameters, fragments, or multiple/missing tokens are rejected. DMC routes only its public release API request or its fixed DMC installer asset through a configured template.

## Trust and integrity

A mirror is a third-party service. It receives the request URL and can observe the client's network address. Use only services you trust.

For the strongest available separation, leave **Update check mirror** set to direct GitHub and choose a mirror only for **Installer download**. Then the expected release version, file length, and SHA-256 come from GitHub's HTTPS API response, while the mirror supplies the installer bytes. If a check mirror is enabled, the release metadata and its SHA-256 value come from that mirror too; matching that value detects transfer errors but does not independently authenticate the publisher.

If a mirror is unavailable or returns an error, select the direct preset for that route. DMC does not silently substitute a different mirror.
