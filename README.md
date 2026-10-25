# Aura AI Agent Clone

This repository contains a manual GitHub Actions workflow to copy the latest files from [luckydev-dev/Aura-Ai-Agent](https://github.com/luckydev-dev/Aura-Ai-Agent).

## Run the clone action

1. Open the **Actions** tab.
2. Select **Clone Aura Repository**.
3. Click **Run workflow**, keep source branch `main`, and run it.
4. When the job succeeds, the source files will be committed to this repository.

The workflow is manual-only. It replaces working files with the source project while retaining its own workflow file. The original source repository is not modified.

If pushing fails, check **Settings → Actions → General → Workflow permissions** and enable **Read and write permissions**.
