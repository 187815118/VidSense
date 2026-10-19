package com.example.server.memory;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** At most one local worker and one claimed job; durable DB leases coordinate multiple instances. */
@Component
@EnableScheduling
public class MemoryMaintenance {
    private static final Logger log=LoggerFactory.getLogger(MemoryMaintenance.class);
    private final MemoryService memory;
    private final MemoryJobRepository jobs;
    private final MemoryIndexService index;
    private final MemoryConsolidationService compression;
    private final boolean enabled;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"memory-maintenance"); t.setDaemon(true); return t;});
    public MemoryMaintenance(MemoryService memory,MemoryJobRepository jobs,MemoryIndexService index,MemoryConsolidationService compression,
                             @Value("${memory.enabled:false}") boolean enabled) {
        this.memory=memory; this.jobs=jobs; this.index=index; this.compression=compression; this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${memory.worker.poll-ms:2000}")
    public void tick() {
        if(!enabled || !running.compareAndSet(false,true)) return;
        try { worker.submit(() -> { try { drainOne(); } catch(RuntimeException e) {
            log.warn("memory_maintenance_retry error={}",e.getClass().getSimpleName());
        } finally {running.set(false);} }); } catch(RejectedExecutionException e) {running.set(false);}
    }
    public void drainOne() {
        if(!enabled) return;
        jobs.recoverCompleted();
        MemoryJobRepository.Job job=jobs.claim();
        if(job!=null) {
            try {
                MemoryJobRepository.Work work=jobs.decode(job);
                if(!jobs.owns(job)) return;
                if(work.feedback()==null) memory.processCompleted(work.request(),work.state());
                else memory.processFeedback(work.request(),work.feedback());
                jobs.done(job);
                log.info("memory_job_completed jobId={}",job.id());
            } catch(Exception e) {
                jobs.failed(job,e);
                log.warn("memory_job_retry jobId={} attempt={} error={}",job.id(),job.attempts(),e.getClass().getSimpleName());
            }
        }
        index.drain(10);
        compression.sweep();
    }
    @PreDestroy public void close() {worker.shutdownNow();}
}
