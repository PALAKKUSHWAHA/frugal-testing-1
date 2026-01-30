Run the Registration Form Locally

This short guide explains how to serve the static `registration-form/` locally and preview it (including Codespaces / port forwarding instructions).

1) Serve the static site locally (Python)

- From the repository root run:

  ```bash
  # Serve files from the registration-form directory on port 8000
  python3 -m http.server 8000 --directory registration-form
  ```

- Open http://127.0.0.1:8000 in your browser.

2) Quick verification

- Check the server response:

  ```bash
  curl -I http://127.0.0.1:8000 | head
  ```

- Expect HTTP/1.1 200 and the HTML content for `/index.html`.

3) Preview in Codespaces / devcontainers (port forwarding)

- In Codespaces: open the **Ports** view, forward port `8000`, and open the forwarded URL (the environment provides a preview URL). The preview URL typically looks like `https://<workspace>-8000.githubpreview.dev` or similar.

- Note: Some preview proxies return HTTP 502 when they cannot reach a running local server. If you see a 502:
  - Ensure the Python server above is still running
  - Confirm port `8000` is forwarded by the environment
  - Try `curl --max-time 2 <forwarded-url>` to confirm reachability

4) Optional: run directly inside `registration-form/`

  ```bash
  cd registration-form && python3 -m http.server 8000
  ```

Security note

- Keep the server bound to `127.0.0.1` for normal development. Only bind to `0.0.0.0` for deliberate debugging and in trusted network environments.

Troubleshooting

- If the page shows a `502` from a preview domain, see steps above. If port forwarding does not work in your environment check your Codespaces / devcontainer UI for forwarded ports and logs.