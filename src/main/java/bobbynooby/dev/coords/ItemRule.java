package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.CustomDataAccessor;
import bobbynooby.dev.mixin.ItemContainerContentsInvoker;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.item.component.SulfurCubeContent;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Positions inside items: a compass's target, a map's markers, the items a container, bundle or crossbow holds, the NBT
 * an item carries, and the items shown in its name, lore and pages. Only an item's own components are looked at, since
 * the client knows each item's defaults. The same rule takes them off again for what a creative client sends.
 */
public final class ItemRule {
    // Filled at mod start, before any server thread runs, and only read after
    private static final Map<DataComponentType<?>, Coverage> COMPONENTS = new HashMap<>();
    private static final List<Rule<?>> RULES = new ArrayList<>();

    private ItemRule() {}

    static void register() {
        classifyComponents();
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_CONTENT, ItemRule::containerContent);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_CONTAINER_SET_SLOT, ItemRule::containerSlot);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_CURSOR_ITEM, ItemRule::cursor);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY, ItemRule::inventorySlot);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_EQUIPMENT, ItemRule::equipment);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_MERCHANT_OFFERS, ItemRule::offers);
        Incoming.translate(GamePacketTypes.SERVERBOUND_SET_CREATIVE_MODE_SLOT, ItemRule::creativeSlot);
        // The hashes of the clicked slots can't be turned back, so the server matches them against shifted stacks too
        Incoming.elsewhere(GamePacketTypes.SERVERBOUND_CONTAINER_CLICK);
    }

    /** Every data component type: TRANSLATED when its value can hold a position, which this rule shifts. */
    public static Map<DataComponentType<?>, Coverage> componentCoverage() {
        return Collections.unmodifiableMap(COMPONENTS);
    }

    /** The stack as the client is sent it: the very stack when none of its own components holds a position. */
    public static ItemStack shift(ItemStack stack, Offset offset) {
        if (offset.isZero()) {
            return stack;
        }
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return stack;
        }
        ItemStack copy = null;
        for (Rule<?> rule : RULES) {
            copy = apply(rule, stack, patch, offset, copy);
        }
        return copy != null ? copy : stack;
    }

    /** The same for an item held inside another, which is a template: the very template when nothing in it moves. */
    public static ItemStackTemplate shift(ItemStackTemplate template, Offset offset) {
        DataComponentPatch patch = shift(template.components(), offset);
        return patch == template.components() ? template : new ItemStackTemplate(template.item(), template.count(), patch);
    }

    private static DataComponentPatch shift(DataComponentPatch patch, Offset offset) {
        if (offset.isZero() || patch.isEmpty()) {
            return patch;
        }
        DataComponentPatch.Builder builder = null;
        for (Rule<?> rule : RULES) {
            builder = apply(rule, patch, offset, builder);
        }
        return builder != null ? builder.build() : patch;
    }

    private static <T> @Nullable ItemStack apply(Rule<T> rule, ItemStack stack, DataComponentPatch patch, Offset offset,
                                                 @Nullable ItemStack copy) {
        T shifted = rule.shifted(patch, offset);
        if (shifted == null) {
            return copy;
        }
        ItemStack target = copy != null ? copy : stack.copy();
        target.set(rule.type(), shifted);
        return target;
    }

    private static <T> DataComponentPatch.@Nullable Builder apply(Rule<T> rule, DataComponentPatch patch, Offset offset,
                                                                  DataComponentPatch.@Nullable Builder builder) {
        T shifted = rule.shifted(patch, offset);
        if (shifted == null) {
            return builder;
        }
        DataComponentPatch.Builder target = builder != null ? builder : copyOf(patch);
        target.set(rule.type(), shifted);
        return target;
    }

    // A builder holding everything the patch does, the components it takes away included
    private static DataComponentPatch.Builder copyOf(DataComponentPatch patch) {
        DataComponentPatch.SplitResult split = patch.split();
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        builder.set(split.added());
        for (DataComponentType<?> removed : split.removed()) {
            builder.remove(removed);
        }
        return builder;
    }

    // A component type whose value can hold a position, and how to shift it: to the very value when nothing in it moves
    private record Rule<T>(DataComponentType<T> type, BiFunction<T, Offset, T> shift) {
        // Null when the item's own components don't set it, or nothing in it moves
        @Nullable T shifted(DataComponentPatch patch, Offset offset) {
            T value = patch.get(DataComponentMap.EMPTY, type);
            if (value == null) {
                return null;
            }
            T shifted = shift.apply(value, offset);
            return shifted == value ? null : shifted;
        }
    }

    private static <T> void translated(DataComponentType<T> type, BiFunction<T, Offset, T> shift) {
        RULES.add(new Rule<>(type, shift));
        COMPONENTS.put(type, Coverage.TRANSLATED);
    }

    private static void noPositions(DataComponentType<?>... types) {
        for (DataComponentType<?> type : types) {
            COMPONENTS.put(type, Coverage.NO_POSITIONS);
        }
    }

    // From each type's value in 26.3. A component with no stream codec of its own goes to the client in its saved form,
    // so a map's markers and custom data are sent too.
    private static void classifyComponents() {
        translated(DataComponents.LODESTONE_TRACKER, ItemRule::tracker);
        translated(DataComponents.MAP_DECORATIONS, ItemRule::decorations);
        translated(DataComponents.CONTAINER, ItemRule::container);
        translated(DataComponents.BUNDLE_CONTENTS, ItemRule::bundle);
        translated(DataComponents.CHARGED_PROJECTILES, ItemRule::projectiles);
        translated(DataComponents.USE_REMAINDER, ItemRule::remainder);
        translated(DataComponents.SULFUR_CUBE_CONTENT, ItemRule::sulfurCube);
        translated(DataComponents.ENTITY_DATA, ItemRule::typedData);
        translated(DataComponents.BLOCK_ENTITY_DATA, ItemRule::typedData);
        translated(DataComponents.BUCKET_ENTITY_DATA, ItemRule::customData);
        // Any NBT a command or a data pack puts on an item
        translated(DataComponents.CUSTOM_DATA, ItemRule::customData);
        translated(DataComponents.BEES, ItemRule::bees);
        // Text, whose words stay as they are, coordinates typed into them included. An item a hover in it shows goes
        // through this rule too.
        translated(DataComponents.CUSTOM_NAME, TextRule::shift);
        translated(DataComponents.ITEM_NAME, TextRule::shift);
        translated(DataComponents.LORE, ItemRule::lore);
        translated(DataComponents.WRITTEN_BOOK_CONTENT, ItemRule::book);
        translated(DataComponents.SIGN_TEXT_FRONT, ItemRule::sign);
        translated(DataComponents.SIGN_TEXT_BACK, ItemRule::sign);
        noPositions(
                DataComponents.MAX_STACK_SIZE,
                DataComponents.MAX_DAMAGE,
                DataComponents.DAMAGE,
                DataComponents.UNBREAKABLE,
                DataComponents.USE_EFFECTS,
                DataComponents.MINIMUM_ATTACK_CHARGE,
                DataComponents.DAMAGE_TYPE,
                DataComponents.ITEM_MODEL,
                DataComponents.RARITY,
                DataComponents.ENCHANTMENTS,
                // block predicates an op writes, which go out as written, like a typed command
                DataComponents.CAN_PLACE_ON,
                DataComponents.CAN_BREAK,
                DataComponents.ATTRIBUTE_MODIFIERS,
                DataComponents.CUSTOM_MODEL_DATA,
                DataComponents.TOOLTIP_DISPLAY,
                DataComponents.REPAIR_COST,
                DataComponents.CREATIVE_SLOT_LOCK,
                DataComponents.ENCHANTMENT_GLINT_OVERRIDE,
                DataComponents.INTANGIBLE_PROJECTILE,
                DataComponents.FOOD,
                DataComponents.CONSUMABLE,
                DataComponents.USE_COOLDOWN,
                DataComponents.DAMAGE_RESISTANT,
                DataComponents.TOOL,
                DataComponents.WEAPON,
                DataComponents.ATTACK_RANGE,
                DataComponents.ENCHANTABLE,
                DataComponents.EQUIPPABLE,
                DataComponents.REPAIRABLE,
                DataComponents.GLIDER,
                DataComponents.TOOLTIP_STYLE,
                DataComponents.DEATH_PROTECTION,
                DataComponents.BLOCKS_ATTACKS,
                DataComponents.PIERCING_WEAPON,
                DataComponents.KINETIC_WEAPON,
                DataComponents.ATTACK_ANIMATION,
                DataComponents.INTERACT_ANIMATION,
                DataComponents.ADDITIONAL_TRADE_COST,
                DataComponents.BLOCK_TRANSFORMER,
                DataComponents.VILLAGER_FOOD,
                DataComponents.STORED_ENCHANTMENTS,
                DataComponents.DYE,
                DataComponents.DYED_COLOR,
                DataComponents.MAP_ID,
                DataComponents.MAP_POST_PROCESSING,
                DataComponents.POTION_CONTENTS,
                DataComponents.POTION_DURATION_SCALE,
                DataComponents.SUSPICIOUS_STEW_EFFECTS,
                DataComponents.WRITABLE_BOOK_CONTENT,
                DataComponents.TRIM,
                DataComponents.DEBUG_STICK_STATE,
                DataComponents.INSTRUMENT,
                DataComponents.PROVIDES_TRIM_MATERIAL,
                DataComponents.OMINOUS_BOTTLE_AMPLIFIER,
                DataComponents.JUKEBOX_PLAYABLE,
                DataComponents.PROVIDES_BANNER_PATTERNS,
                DataComponents.RECIPES,
                DataComponents.FIREWORK_EXPLOSION,
                DataComponents.FIREWORKS,
                DataComponents.PROFILE,
                DataComponents.NOTE_BLOCK_SOUND,
                DataComponents.BANNER_PATTERNS,
                DataComponents.BASE_COLOR,
                DataComponents.POT_DECORATIONS,
                DataComponents.BLOCK_STATE,
                // an item predicate, as for the place and break predicates
                DataComponents.LOCK,
                DataComponents.CONTAINER_LOOT,
                DataComponents.BREAK_SOUND,
                DataComponents.COMPOSTABLE,
                DataComponents.COOKING_FUEL,
                DataComponents.BREWING_FUEL,
                DataComponents.MOB_VISIBILITY,
                DataComponents.VILLAGER_VARIANT,
                DataComponents.WOLF_VARIANT,
                DataComponents.WOLF_SOUND_VARIANT,
                DataComponents.WOLF_COLLAR,
                DataComponents.FOX_VARIANT,
                DataComponents.SALMON_SIZE,
                DataComponents.PARROT_VARIANT,
                DataComponents.TROPICAL_FISH_PATTERN,
                DataComponents.TROPICAL_FISH_BASE_COLOR,
                DataComponents.TROPICAL_FISH_PATTERN_COLOR,
                DataComponents.MOOSHROOM_VARIANT,
                DataComponents.RABBIT_VARIANT,
                DataComponents.PIG_VARIANT,
                DataComponents.PIG_SOUND_VARIANT,
                DataComponents.COW_VARIANT,
                DataComponents.COW_SOUND_VARIANT,
                DataComponents.CHICKEN_VARIANT,
                DataComponents.CHICKEN_SOUND_VARIANT,
                DataComponents.ZOMBIE_NAUTILUS_VARIANT,
                DataComponents.FROG_VARIANT,
                DataComponents.HORSE_VARIANT,
                DataComponents.PAINTING_VARIANT,
                DataComponents.LLAMA_VARIANT,
                DataComponents.AXOLOTL_VARIANT,
                DataComponents.CAT_VARIANT,
                DataComponents.CAT_SOUND_VARIANT,
                DataComponents.CAT_COLLAR,
                DataComponents.SHEEP_COLOR,
                DataComponents.SHULKER_COLOR,
                DataComponents.PROVIDES_POTTERY_PATTERN,
                DataComponents.WAXED,
                DataComponents.CUSHION_COLOR);
    }

    // Shifted whatever the target's dimension, so no real number goes out; the needle spins there anyway
    private static LodestoneTracker tracker(LodestoneTracker tracker, Offset offset) {
        return tracker.target().isEmpty() ? tracker
                : new LodestoneTracker(tracker.target().map(target -> offset.shift(target)), tracker.tracked());
    }

    // An explorer map's markers, kept in the order they came in
    private static MapDecorations decorations(MapDecorations decorations, Offset offset) {
        if (decorations.decorations().isEmpty()) {
            return decorations;
        }
        Map<String, MapDecorations.Entry> shifted = new LinkedHashMap<>();
        decorations.decorations().forEach((id, entry) -> shifted.put(id, new MapDecorations.Entry(entry.type(),
                offset.shiftX(entry.x()), offset.shiftZ(entry.z()), entry.rotation())));
        return new MapDecorations(Collections.unmodifiableMap(shifted));
    }

    // Every slot keeps its place, empty ones too, so the copy is built from the slot list itself
    private static ItemContainerContents container(ItemContainerContents contents, Offset offset) {
        List<Optional<ItemStackTemplate>> slots = ((ItemContainerContentsInvoker) (Object) contents).yoltItems();
        List<Optional<ItemStackTemplate>> shifted = CoordinateOffsets.mapEach(slots, slot -> {
            if (slot.isEmpty()) {
                return slot;
            }
            ItemStackTemplate moved = shift(slot.get(), offset);
            return moved == slot.get() ? slot : Optional.of(moved);
        });
        return shifted == slots ? contents : ItemContainerContentsInvoker.yoltNew(shifted);
    }

    // In order, as the client shows a bundle's items in the order they went in
    private static BundleContents bundle(BundleContents bundle, Offset offset) {
        List<ItemStackTemplate> shifted = CoordinateOffsets.mapEach(bundle.items(), template -> shift(template, offset));
        return shifted == bundle.items() ? bundle : new BundleContents(shifted);
    }

    private static ChargedProjectiles projectiles(ChargedProjectiles projectiles, Offset offset) {
        List<ItemStackTemplate> shifted = CoordinateOffsets.mapEach(projectiles.items(), template -> shift(template, offset));
        return shifted == projectiles.items() ? projectiles : new ChargedProjectiles(shifted);
    }

    private static UseRemainder remainder(UseRemainder remainder, Offset offset) {
        ItemStackTemplate shifted = shift(remainder.convertInto(), offset);
        return shifted == remainder.convertInto() ? remainder : new UseRemainder(shifted);
    }

    private static SulfurCubeContent sulfurCube(SulfurCubeContent content, Offset offset) {
        ItemStackTemplate shifted = shift(content.absorbedBlockItemStack(), offset);
        return shifted == content.absorbedBlockItemStack() ? content : new SulfurCubeContent(shifted);
    }

    // Only read here, and the NBT rule never changes a tag it is given
    @SuppressWarnings("deprecation")
    private static <T> TypedEntityData<T> typedData(TypedEntityData<T> data, Offset offset) {
        CompoundTag tag = data.getUnsafe();
        CompoundTag shifted = NbtRule.shift(tag, offset);
        return shifted == tag ? data : TypedEntityData.of(data.type(), shifted);
    }

    private static ItemLore lore(ItemLore lore, Offset offset) {
        List<Component> lines = CoordinateOffsets.mapEach(lore.lines(), line -> TextRule.shift(line, offset));
        return lines == lore.lines() ? lore : new ItemLore(lines);
    }

    // The title and author are plain strings; each page keeps its filtered form
    private static WrittenBookContent book(WrittenBookContent book, Offset offset) {
        List<Filterable<Component>> pages = CoordinateOffsets.mapEach(book.pages(), page -> {
            Component raw = TextRule.shift(page.raw(), offset);
            Optional<Component> filtered = TextRule.shift(page.filtered(), offset);
            return raw == page.raw() && filtered == page.filtered() ? page : new Filterable<>(raw, filtered);
        });
        return pages == book.pages() ? book
                : new WrittenBookContent(book.title(), book.author(), book.generation(), pages, book.resolved());
    }

    // A sign item's lines, with the filtered ones kept the same list when they are
    private static SignText sign(SignText text, Offset offset) {
        List<Component> lines = text.getMessages(false);
        List<Component> shifted = CoordinateOffsets.mapEach(lines, line -> TextRule.shift(line, offset));
        List<Component> filtered = text.getMessages(true) == lines ? shifted
                : CoordinateOffsets.mapEach(text.getMessages(true), line -> TextRule.shift(line, offset));
        return shifted == lines && filtered == text.getMessages(true) ? text
                : new SignText(shifted, filtered, text.getColor(), text.hasGlowingText());
    }

    // Read in place, as for entity data
    private static CustomData customData(CustomData data, Offset offset) {
        CompoundTag tag = ((CustomDataAccessor) (Object) data).yoltTag();
        CompoundTag shifted = NbtRule.shift(tag, offset);
        return shifted == tag ? data : CustomData.of(shifted);
    }

    // A bee's flower is kept in its entity data
    private static Bees bees(Bees bees, Offset offset) {
        List<BeehiveBlockEntity.Occupant> shifted = CoordinateOffsets.mapEach(bees.bees(), bee -> {
            TypedEntityData<EntityType<?>> data = typedData(bee.entityData(), offset);
            return data == bee.entityData() ? bee : new BeehiveBlockEntity.Occupant(data, bee.ticksInHive(), bee.minTicksInHive());
        });
        return shifted == bees.bees() ? bees : new Bees(shifted);
    }

    private static Packet<?> containerContent(ClientboundContainerSetContentPacket packet, Context context) {
        Offset offset = context.offset();
        List<ItemStack> items = CoordinateOffsets.mapEach(packet.items(), stack -> shift(stack, offset));
        ItemStack carried = shift(packet.carriedItem(), offset);
        if (items == packet.items() && carried == packet.carriedItem()) {
            return packet;
        }
        return new ClientboundContainerSetContentPacket(packet.containerId(), packet.stateId(), items, carried);
    }

    private static Packet<?> containerSlot(ClientboundContainerSetSlotPacket packet, Context context) {
        ItemStack stack = shift(packet.getItem(), context.offset());
        return stack == packet.getItem() ? packet
                : new ClientboundContainerSetSlotPacket(packet.getContainerId(), packet.getStateId(), packet.getSlot(), stack);
    }

    private static Packet<?> cursor(ClientboundSetCursorItemPacket packet, Context context) {
        ItemStack stack = shift(packet.contents(), context.offset());
        return stack == packet.contents() ? packet : new ClientboundSetCursorItemPacket(stack);
    }

    private static Packet<?> inventorySlot(ClientboundSetPlayerInventoryPacket packet, Context context) {
        ItemStack stack = shift(packet.contents(), context.offset());
        return stack == packet.contents() ? packet : new ClientboundSetPlayerInventoryPacket(packet.slot(), stack);
    }

    // What another player or a mob holds and wears, such as a compass in someone's hand
    private static Packet<?> equipment(ClientboundSetEquipmentPacket packet, Context context) {
        Offset offset = context.offset();
        List<Pair<EquipmentSlot, ItemStack>> slots = packet.getSlots();
        List<Pair<EquipmentSlot, ItemStack>> shifted = CoordinateOffsets.mapEach(slots, slot -> {
            ItemStack moved = shift(slot.getSecond(), offset);
            return moved == slot.getSecond() ? slot : Pair.of(slot.getFirst(), moved);
        });
        return shifted == slots ? packet : new ClientboundSetEquipmentPacket(packet.getEntity(), shifted);
    }

    // Only a data pack could put a position in what a trade gives
    private static Packet<?> offers(ClientboundMerchantOffersPacket packet, Context context) {
        Offset offset = context.offset();
        MerchantOffers offers = packet.getOffers();
        List<MerchantOffer> shifted = CoordinateOffsets.mapEach(offers, offer -> {
            ItemStack result = shift(offer.getResult(), offset);
            return result == offer.getResult() ? offer : withResult(offer, result);
        });
        if (shifted == offers) {
            return packet;
        }
        MerchantOffers copy = new MerchantOffers();
        copy.addAll(shifted);
        return new ClientboundMerchantOffersPacket(packet.getContainerId(), copy, packet.getVillagerLevel(),
                packet.getVillagerXp(), packet.showProgress(), packet.canRestock());
    }

    // Everything the client is sent of the offer, its uses and price changes included
    private static MerchantOffer withResult(MerchantOffer offer, ItemStack result) {
        MerchantOffer copy = new MerchantOffer(offer.getItemCostA(), offer.getItemCostB(), result, offer.getUses(),
                offer.getMaxUses(), offer.getXp(), offer.getPriceMultiplier(), offer.getDemand());
        copy.setSpecialPriceDiff(offer.getSpecialPriceDiff());
        return copy;
    }

    // A creative client sends whole stacks, made from the shifted ones it was sent
    private static Packet<?> creativeSlot(ServerboundSetCreativeModeSlotPacket packet, Context context) {
        ItemStack stack = shift(packet.itemStack(), context.offset().negate());
        return stack == packet.itemStack() ? packet : new ServerboundSetCreativeModeSlotPacket(packet.slotNum(), stack);
    }
}
