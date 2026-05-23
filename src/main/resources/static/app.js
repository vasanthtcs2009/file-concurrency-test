// UI DOM Elements
const btnGenerate = document.getElementById('btn-generate');
const btnImport = document.getElementById('btn-import');
const btnClear = document.getElementById('btn-clear');
const btnRefreshData = document.getElementById('btn-refresh-data');

const selectRecordCount = document.getElementById('record-count');
const selectBatchSize = document.getElementById('batch-size');

const progressPanel = document.getElementById('progress-panel');
const progressText = document.getElementById('progress-text');
const progressPercentage = document.getElementById('progress-percentage');
const progressFill = document.getElementById('progress-fill');

const statThroughput = document.getElementById('stat-throughput');
const statThreads = document.getElementById('stat-threads');
const statProcessed = document.getElementById('stat-processed');
const statDbCount = document.getElementById('stat-db-count');
const statElapsed = document.getElementById('stat-elapsed');
const statFileSize = document.getElementById('stat-file-size');

const statusDot = document.getElementById('status-dot');
const statusText = document.getElementById('status-text');
const logsConsole = document.getElementById('logs-console');

const tableBody = document.getElementById('table-body');

let activeJobId = null;
let statusInterval = null;

// Logger function
function logMessage(message, type = 'info') {
    const time = new Date().toLocaleTimeString();
    const span = document.createElement('span');
    span.className = type;
    span.innerText = `[${time}] ${message}\n`;
    logsConsole.appendChild(span);
    logsConsole.scrollTop = logsConsole.scrollHeight;
}

// Initial status load
async function fetchCurrentStatus() {
    try {
        const response = await fetch('/api/telemetry/status');
        const data = await response.json();
        statDbCount.innerText = data.dbRowCount.toLocaleString();
        
        // If there are running jobs, reconnect to them
        if (data.jobs && data.jobs.length > 0) {
            const runningJob = data.jobs.find(j => j.status === 'RUNNING' || j.status === 'PENDING');
            if (runningJob) {
                activeJobId = runningJob.jobId;
                statFileSize.innerText = `${runningJob.fileSizeMb} MB`;
                btnGenerate.disabled = true;
                btnImport.disabled = true;
                btnClear.disabled = true;
                progressPanel.style.display = 'block';
                progressFill.classList.add('active');
                
                logMessage(`Found active ingestion job: ${activeJobId}. Resuming monitor...`);
                startPolling(activeJobId);
            }
        }
    } catch (err) {
        logMessage(`Failed to contact API server: ${err.message}`, 'error');
    }
}

// Start polling status
function startPolling(jobId) {
    if (statusInterval) clearInterval(statusInterval);
    
    statusInterval = setInterval(async () => {
        try {
            const response = await fetch(`/api/telemetry/status/${jobId}`);
            if (response.status === 404) {
                clearInterval(statusInterval);
                return;
            }
            const job = await response.json();
            updateMetrics(job);
            
            if (job.status === 'COMPLETED') {
                clearInterval(statusInterval);
                onJobCompleted(job);
            } else if (job.status === 'FAILED') {
                clearInterval(statusInterval);
                onJobFailed(job);
            }
        } catch (err) {
            logMessage(`Polling error: ${err.message}`, 'error');
        }
    }, 200);
}

// Update UI metrics from job status
function updateMetrics(job) {
    // Engine status badge
    statusText.innerText = job.status;
    statusDot.className = 'status-dot ' + job.status.toLowerCase();
    
    // Performance numbers
    statThroughput.innerHTML = `${job.throughputRecordsPerSec.toLocaleString()} <span style="font-size: 0.8rem; font-weight: normal;">rec/s</span>`;
    statThreads.innerText = job.activeWriteThreads;
    statProcessed.innerText = `${job.recordsWritten.toLocaleString()} / ${job.recordsRead.toLocaleString()}`;
    statDbCount.innerText = job.dbRowCount.toLocaleString();
    statElapsed.innerText = `${(job.elapsedTimeMs / 1000).toFixed(1)}s`;
    statFileSize.innerText = `${job.fileSizeMb} MB`;
    
    // Progress bar
    let percentage = 0;
    if (job.recordsRead > 0) {
        percentage = Math.round((job.recordsWritten / job.recordsRead) * 100);
    }
    // Safeguard, parser might read faster than db writes, show write progress
    progressPercentage.innerText = `${percentage}%`;
    progressFill.style.width = `${percentage}%`;
    progressText.innerText = `Writing records to PostgreSQL (Virtual Threads)...`;
}

function onJobCompleted(job) {
    btnGenerate.disabled = false;
    btnImport.disabled = true; // Needs regeneration to run again
    btnClear.disabled = false;
    progressFill.classList.remove('active');
    progressText.innerText = 'Ingestion Completed Successfully!';
    
    logMessage(`Ingestion completed in ${(job.elapsedTimeMs / 1000).toFixed(2)} seconds.`, 'success');
    logMessage(`Ingested ${job.recordsWritten.toLocaleString()} records into database.`, 'success');
    logMessage(`Average Write Throughput: ${job.throughputRecordsPerSec.toLocaleString()} records/sec.`, 'success');
    
    const hasPostgres = job.target === 'postgres' || job.target === 'both';
    const hasRedis = job.target === 'redis' || job.target === 'both';
    
    if (hasPostgres) {
        const avgPgBatch = (job.writeTimeMs / Math.max(1, job.recordsWritten / job.batchSize)).toFixed(1);
        logMessage(`Postgres: Cumulative Write Time = ${job.writeTimeMs} ms, Avg Batch Write Time = ${avgPgBatch} ms/batch.`, 'info');
    }
    if (hasRedis) {
        const avgRedisBatch = (job.redisWriteTimeMs / Math.max(1, job.recordsWritten / job.batchSize)).toFixed(1);
        logMessage(`Redis (${job.redisStrategy}): Cumulative Write Time = ${job.redisWriteTimeMs} ms, Avg Batch Write Time = ${avgRedisBatch} ms/batch.`, 'info');
    }
    
    refreshSampleData();
}

function onJobFailed(job) {
    btnGenerate.disabled = false;
    btnImport.disabled = false;
    btnClear.disabled = false;
    progressFill.classList.remove('active');
    progressText.innerText = 'Ingestion Pipeline Failed';
    progressFill.style.backgroundColor = 'var(--danger)';
    
    logMessage(`Ingestion failed: ${job.errorMessage}`, 'error');
}

// Generate JSON mock file
btnGenerate.addEventListener('click', async () => {
    const count = selectRecordCount.value;
    btnGenerate.disabled = true;
    btnImport.disabled = true;
    btnClear.disabled = true;
    
    statusText.innerText = 'GENERATING';
    statusDot.className = 'status-dot running';
    logMessage(`Generating mock JSON file with ${parseInt(count).toLocaleString()} records...`);
    
    try {
        const response = await fetch(`/api/telemetry/generate?count=${count}`, { method: 'POST' });
        const data = await response.json();
        
        if (response.ok) {
            statFileSize.innerText = `${data.fileSizeMb} MB`;
            logMessage(`Mock data file generated successfully.`, 'success');
            logMessage(`File location: ${data.filePath}`, 'info');
            logMessage(`File Size: ${data.fileSizeMb} MB (Time taken: ${(data.durationMs / 1000).toFixed(2)}s).`, 'info');
            
            btnImport.disabled = false;
        } else {
            logMessage(`Generation failed: ${data.error}`, 'error');
        }
    } catch (err) {
        logMessage(`Generation error: ${err.message}`, 'error');
    } finally {
        btnGenerate.disabled = false;
        btnClear.disabled = false;
        statusText.innerText = 'IDLE';
        statusDot.className = 'status-dot pending';
    }
});

// Import trigger
btnImport.addEventListener('click', async () => {
    const batchSize = selectBatchSize.value;
    btnGenerate.disabled = true;
    btnImport.disabled = true;
    btnClear.disabled = true;
    
    progressPanel.style.display = 'block';
    progressFill.style.width = '0%';
    progressFill.style.backgroundColor = '';
    progressFill.classList.add('active');
    progressPercentage.innerText = '0%';
    progressText.innerText = 'Starting parser stream...';
    
    logMessage(`Starting ingestion with JDBC batch size of ${batchSize}...`);
    
    try {
        const response = await fetch(`/api/telemetry/import?batchSize=${batchSize}`, { method: 'POST' });
        const data = await response.json();
        
        if (response.ok) {
            activeJobId = data.jobId;
            logMessage(`Ingestion job started. Tracking ID: ${activeJobId}`, 'info');
            startPolling(activeJobId);
        } else {
            logMessage(`Import failed: ${data.error}`, 'error');
            btnGenerate.disabled = false;
            btnImport.disabled = false;
            btnClear.disabled = false;
            progressPanel.style.display = 'none';
        }
    } catch (err) {
        logMessage(`Import error: ${err.message}`, 'error');
        btnGenerate.disabled = false;
        btnImport.disabled = false;
        btnClear.disabled = false;
        progressPanel.style.display = 'none';
    }
});

// Wipe DB & File
btnClear.addEventListener('click', async () => {
    if (!confirm('Are you sure you want to delete the local JSON file and clear the database?')) {
        return;
    }
    
    try {
        const response = await fetch('/api/telemetry/clear', { method: 'DELETE' });
        const data = await response.json();
        
        if (response.ok) {
            statDbCount.innerText = '0';
            statFileSize.innerText = '0.0 MB';
            statThroughput.innerHTML = '0.00 <span style="font-size: 0.8rem; font-weight: normal;">rec/s</span>';
            statProcessed.innerText = '0 / 0';
            statElapsed.innerText = '0.0s';
            statThreads.innerText = '0';
            
            progressPanel.style.display = 'none';
            btnImport.disabled = true;
            
            tableBody.innerHTML = `
                <tr>
                    <td colspan="9" style="text-align: center; color: var(--text-secondary); padding: 2rem;">No data in database. Run ingestion pipeline.</td>
                </tr>
            `;
            
            logMessage('Database truncated and mock JSON file deleted.', 'success');
        } else {
            logMessage(`Clear failed: ${data.error}`, 'error');
        }
    } catch (err) {
        logMessage(`Clear error: ${err.message}`, 'error');
    }
});

// Refresh table data
async function refreshSampleData() {
    logMessage('Refreshing sample data from database...');
    try {
        const response = await fetch('/api/telemetry/sample');
        const data = await response.json();
        
        if (data.length === 0) {
            tableBody.innerHTML = `
                <tr>
                    <td colspan="9" style="text-align: center; color: var(--text-secondary); padding: 2rem;">No data in database. Run ingestion pipeline.</td>
                </tr>
            `;
            return;
        }
        
        tableBody.innerHTML = '';
        data.forEach(row => {
            const tr = document.createElement('tr');
            
            // Format ID, DeviceID, Timestamp, Status
            const tdId = document.createElement('td');
            tdId.innerText = row.id.substring(0, 8) + '...';
            tdId.title = row.id;
            tr.appendChild(tdId);
            
            const tdDev = document.createElement('td');
            tdDev.innerText = row.device_id;
            tr.appendChild(tdDev);
            
            const tdTime = document.createElement('td');
            tdTime.innerText = new Date(row.timestamp).toLocaleString();
            tr.appendChild(tdTime);
            
            const tdStat = document.createElement('td');
            tdStat.innerHTML = `<span style="color: ${
                row.status === 'OK' ? 'var(--success)' : 
                row.status === 'WARNING' ? 'var(--warning)' : 'var(--danger)'
            }; font-weight: 600;">${row.status}</span>`;
            tr.appendChild(tdStat);
            
            // Render first 3 metrics, ellipsis, and last metric
            for (let i = 1; i <= 3; i++) {
                const tdMet = document.createElement('td');
                tdMet.innerText = row[`metric_${i}`]?.toFixed(2) || '0.00';
                tr.appendChild(tdMet);
            }
            
            const tdEllipsis = document.createElement('td');
            tdEllipsis.innerText = '...';
            tr.appendChild(tdEllipsis);
            
            const tdLast = document.createElement('td');
            tdLast.innerText = row['metric_146']?.toFixed(2) || '0.00';
            tr.appendChild(tdLast);
            
            tableBody.appendChild(tr);
        });
        
        logMessage(`Loaded ${data.length} sample records into explorer view.`, 'success');
    } catch (err) {
        logMessage(`Failed to fetch sample data: ${err.message}`, 'error');
    }
}

btnRefreshData.addEventListener('click', refreshSampleData);

// Page load initialization
window.addEventListener('DOMContentLoaded', () => {
    fetchCurrentStatus();
    refreshSampleData();
});
