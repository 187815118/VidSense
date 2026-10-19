package com.example.server.memory;

import com.example.server.service.AgentTelemetry;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryModelBudgetTest {
    @Test void actualFutureDeadlineCancelsWithoutWaitingForProviderTimeout() {
        var executor=mock(ThreadPoolTaskExecutor.class);
        FutureTask<String> pending=new FutureTask<>(()->"never started");
        when(executor.submit(org.mockito.ArgumentMatchers.<Callable<String>>any())).thenReturn(pending);
        var model=new DeepSeekUtils("test","http://127.0.0.1:1","test",300,0,0,0,
                mock(AgentTelemetry.class),new ObjectMapper(),executor);
        long start=System.nanoTime();
        assertThrows(IllegalStateException.class,()->model.memoryJson("extract","{}",50));
        assertTrue((System.nanoTime()-start)/1_000_000<1000);
        assertTrue(pending.isCancelled());
        verify(executor,times(1)).submit(org.mockito.ArgumentMatchers.<Callable<String>>any());
    }
}
