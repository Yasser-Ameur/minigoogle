param(
    [string]$TargetDir = "data/beir"
)

$ErrorActionPreference = "Stop"
$base = "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets"

# Dataset names with the official md5 from the BEIR wiki (verified checksums).
$datasets = @(
    @{ Name = "trec-covid"; Md5 = "ce62140cb23feb9becf6270d0d1fe6d1" },
    @{ Name = "scifact";    Md5 = "5f7d1de60b170fc8027bb7898e2efca1" },
    @{ Name = "nfcorpus";   Md5 = "a89dba18a62ef92f7d323ec890a0d38d" }
)

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

foreach ($d in $datasets) {
    $name = $d.Name
    $zip = Join-Path $TargetDir "$name.zip"
    $dir = Join-Path $TargetDir $name
    $url = "$base/$name.zip"

    if (-not (Test-Path $zip)) {
        Write-Host "[$name] Downloading $url ..."
        Invoke-WebRequest -Uri $url -OutFile $zip
    } else {
        Write-Host "[$name] $zip present; verifying checksum ..."
    }

    $hash = (Get-FileHash -Algorithm MD5 -Path $zip).Hash.ToLower()
    if ($hash -ne $d.Md5) {
        throw "[$name] MD5 mismatch: expected $($d.Md5), got $hash. Delete $zip and retry."
    }
    Write-Host "[$name] MD5 OK: $hash"

    if (-not (Test-Path (Join-Path $dir "corpus.jsonl"))) {
        Write-Host "[$name] Extracting ..."
        Expand-Archive -Path $zip -DestinationPath $dir -Force
        # BEIR zips wrap the files in a top-level folder named after the dataset.
        $nested = Join-Path $dir $name
        if (Test-Path $nested) {
            Get-ChildItem $nested | Move-Item -Destination $dir -Force
            Remove-Item $nested -Recurse -Force
        }
    } else {
        Write-Host "[$name] Already extracted."
    }

    $corpus = Join-Path $dir "corpus.jsonl"
    $queries = Join-Path $dir "queries.jsonl"
    $corpusLines = (Get-Content $corpus -ReadCount 0).Count
    $queryLines = (Get-Content $queries -ReadCount 0).Count
    Write-Host "[$name] lines: corpus=$corpusLines queries=$queryLines"
    Get-ChildItem (Join-Path $dir "qrels") -Filter *.tsv -ErrorAction SilentlyContinue |
        ForEach-Object {
            $lines = (Get-Content $_.FullName -ReadCount 0).Count
            Write-Host "[$name] qrels/$($_.Name): $lines lines"
        }
}

Write-Host "Done. Datasets under $TargetDir"
