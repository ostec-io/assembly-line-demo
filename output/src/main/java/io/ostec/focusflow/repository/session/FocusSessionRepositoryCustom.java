package io.ostec.focusflow.repository.session;

import io.ostec.focusflow.document.session.FocusSessionDocument;

public interface FocusSessionRepositoryCustom {

    FocusSessionDocument saveWithExpectedVersion(FocusSessionDocument doc, long expectedVersion);
}
