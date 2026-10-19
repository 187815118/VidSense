package com.example.server.controller;

import com.example.server.memory.*;
import com.example.server.service.MediaService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryReadControllerTest {
    final MemoryService memory=mock(MemoryService.class);
    final MemoryRepository repository=mock(MemoryRepository.class);
    final MediaService media=mock(MediaService.class);
    final MemoryReadController controller=new MemoryReadController(memory,repository,media);

    @Test void disabledMemoryDoesNotReadSql() {
        var view=controller.overview(1L,null,20).data();
        assertFalse(view.enabled());
        assertTrue(view.items().isEmpty());
        verifyNoInteractions(repository);
    }
    @Test void videoOwnershipIsRequiredBeforeReadingMemory() {
        when(memory.enabled()).thenReturn(true);
        when(media.requireOwnedMedia(7L,2L)).thenThrow(new SecurityException("owner mismatch"));
        assertThrows(SecurityException.class,()->controller.overview(2L,7L,20));
        verifyNoInteractions(repository);
    }
    @Test void authenticatedUserAndServerSelectedSessionScopeAreUsed() {
        when(memory.enabled()).thenReturn(true);
        when(repository.active(2L,7L,null)).thenReturn(List.of());
        assertTrue(controller.overview(2L,7L,20).data().enabled());
        verify(media).requireOwnedMedia(7L,2L);
        verify(repository).active(2L,7L,null);
        verifyNoMoreInteractions(repository);
    }
    @Test void boundedLimitRejectsInvalidRequestsBeforeIo() {
        assertThrows(IllegalArgumentException.class,()->controller.overview(2L,null,0));
        assertThrows(IllegalArgumentException.class,()->controller.overview(2L,null,101));
        verifyNoInteractions(memory,repository,media);
    }
}
