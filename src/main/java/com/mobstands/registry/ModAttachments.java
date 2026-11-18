package com.mobstands.registry;

import com.mobstands.MobStands;
import com.mobstands.stand.StandData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MobStands.MODID);

    /** Saved with the player and copied on death, so a Stand is permanently bound to its owner. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<StandData>> STAND_DATA =
            ATTACHMENT_TYPES.register("stand_data",
                    () -> AttachmentType.serializable(StandData::new).copyOnDeath().build());

    private ModAttachments() {}
}
