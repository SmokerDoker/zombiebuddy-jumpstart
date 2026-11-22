# CI for the fork (proposal)

`jumpstart-build.yml` builds the fork on GitHub Actions and runs `unitTest`, `test_vanilla`,
`test_patched` and `test_hotswap`. It downloads `projectzomboid.jar` of the dedicated server
(Steam app 380870, anonymous login) with DepotDownloader, like BiteCheck's CI.

To turn it on:

```powershell
mkdir .github\workflows, .github\scripts -Force
move tools\ci\jumpstart-build.yml .github\workflows\
move tools\ci\depotdownloader.sh .github\scripts\
```

Then commit, push, and enable Actions for the fork (GitHub → Actions tab: forks start with
Actions disabled).
