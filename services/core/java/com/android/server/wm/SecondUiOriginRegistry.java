/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.android.server.wm;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Launch-origin bookkeeping. Every call is guarded by the ATMS global lock. */
final class SecondUiOriginRegistry {
    static final class DisplayIdentity {
        final int displayId;
        final boolean virtual;
        final String uniqueId;
        final int ownerUid;

        DisplayIdentity(int displayId, boolean virtual, String uniqueId, int ownerUid) {
            this.displayId = displayId;
            this.virtual = virtual;
            this.uniqueId = uniqueId;
            this.ownerUid = ownerUid;
        }

        boolean isRegistrable() {
            return displayId > 0 && virtual && uniqueId != null && !uniqueId.isEmpty()
                    && ownerUid >= 0;
        }

        boolean sameDisplay(DisplayIdentity other) {
            return other != null && other.isRegistrable() && displayId == other.displayId
                    && ownerUid == other.ownerUid && Objects.equals(uniqueId, other.uniqueId);
        }
    }

    private static final class Registration {
        final DisplayIdentity display;

        Registration(DisplayIdentity display) {
            this.display = display;
        }
    }

    static final class Origin {
        private final Registration registration;
        final Object rootToken;

        private Origin(Registration registration, Object rootToken) {
            this.registration = registration;
            this.rootToken = rootToken;
        }
    }

    private final Map<Integer, Registration> mRegistrations = new HashMap<>();

    void register(DisplayIdentity display) {
        if (display == null || !display.isRegistrable()) {
            throw new IllegalArgumentException("A live non-primary virtual display is required");
        }
        final Registration old = mRegistrations.get(display.displayId);
        if (old != null && old.display.sameDisplay(display)) return;
        mRegistrations.put(display.displayId, new Registration(display));
    }

    void unregister(int displayId) {
        mRegistrations.remove(displayId);
    }

    boolean isRegistered(DisplayIdentity liveDisplay) {
        if (liveDisplay == null) return false;
        if (!liveDisplay.isRegistrable()) {
            mRegistrations.remove(liveDisplay.displayId);
            return false;
        }
        final Registration registration = mRegistrations.get(liveDisplay.displayId);
        if (registration == null) return false;
        if (!registration.display.sameDisplay(liveDisplay)) {
            mRegistrations.remove(liveDisplay.displayId);
            return false;
        }
        return true;
    }

    Origin inheritOrCreate(Origin source, boolean hasSourceActivity,
            DisplayIdentity target, Object newRootToken, int launchingUid,
            boolean privilegedLauncher) {
        // Navigation preserves an original identity, including a revoked identity.
        // A non-origin activity moved onto a registered display must not gain one.
        if (hasSourceActivity) return source;
        if (newRootToken == null || !isRegistered(target)) return null;
        if (!privilegedLauncher && launchingUid != target.ownerUid) return null;
        return new Origin(mRegistrations.get(target.displayId), newRootToken);
    }

    boolean isOriginValid(Origin origin, DisplayIdentity liveDisplay) {
        return origin != null && origin.rootToken != null && isRegistered(liveDisplay)
                && mRegistrations.get(liveDisplay.displayId) == origin.registration;
    }
}
