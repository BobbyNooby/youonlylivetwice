package bobbynooby.dev.gametest;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.coords.CoordinateOffsets;
import bobbynooby.dev.coords.Offset;
import bobbynooby.dev.mixin.PlayerInfoUpdatePacketAccessor;
import bobbynooby.dev.mixin.SetPlayerTeamPacketInvoker;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundServerDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Items shown in text: a hover that shows an item carries all of it, a lodestone compass's target included. Each test
// joins players whose packets it reads, so the class has a batch of its own.
public class CoordinateOffsetTextGameTests {
    private static final String TEXT = "you-only-live-twice-gametest:coordinate_offset_text";
    // Where the compass points, far from any test
    private static final BlockPos TARGET = new BlockPos(8_123, 64, -2_345);

    // A player killed by someone holding a named compass is told so with the compass in the message, and so is everyone
    // else. Death messages for all, and no lockout or grave, so the death is only vanilla's.
    @GameTest(environment = TEXT)
    public void deathMessageShowsTheShiftedCompassTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameRules rules = level.getGameRules();
        YoltConfig config = Settings.get();
        boolean messagesBefore = rules.get(GameRules.SHOW_DEATH_MESSAGES);
        boolean pvpBefore = rules.get(GameRules.PVP);
        boolean lockoutBefore = config.deathLockout.enabled;
        boolean gravesBefore = config.graves.enabled;
        boolean hiddenBefore = config.hide.gameMessages;
        ServerPlayer killer = TestPlayers.join(helper, "ctkiller", GameType.SURVIVAL, new BlockPos(3, 1, 3));
        ServerPlayer victim = null;
        ServerPlayer bystander = null;
        try {
            rules.set(GameRules.SHOW_DEATH_MESSAGES, true, server);
            rules.set(GameRules.PVP, true, server);
            config.deathLockout.enabled = false;
            config.graves.enabled = false;
            config.hide.gameMessages = false;
            victim = OffsetTestKit.join(helper, "ctvictim", GameType.SURVIVAL);
            bystander = OffsetTestKit.join(helper, "ctbystander", GameType.CREATIVE);
            ItemStack compass = compass();
            compass.set(DataComponents.CUSTOM_NAME, Component.literal("Home"));
            killer.setItemInHand(InteractionHand.MAIN_HAND, compass);
            TestPlayers.markLoaded(victim);
            int victimMark = OffsetTestKit.mark(victim);
            int bystanderMark = OffsetTestKit.mark(bystander);
            victim.hurtServer(level, level.damageSources().playerAttack(killer), Float.MAX_VALUE);
            helper.assertTrue(victim.isDeadOrDying(), "the victim did not die");

            Component told = OffsetTestKit.last(helper, victim, victimMark, ClientboundPlayerCombatKillPacket.class).message();
            helper.assertValueEqual(targets(told), List.of(CoordinateOffsets.offset(victim).shift(TARGET)),
                    "compass targets in the victim's death screen");
            ServerPlayer watcher = bystander;
            List<Component> heard = OffsetTestKit.sent(bystander, bystanderMark, ClientboundSystemChatPacket.class).stream()
                    .map(ClientboundSystemChatPacket::content)
                    .filter(text -> !targets(text).isEmpty())
                    .toList();
            helper.assertValueEqual(heard.size(), 1, "death messages with a compass the bystander got");
            helper.assertValueEqual(targets(heard.getFirst()), List.of(CoordinateOffsets.offset(watcher).shift(TARGET)),
                    "compass targets in the bystander's death message");
        } finally {
            rules.set(GameRules.SHOW_DEATH_MESSAGES, messagesBefore, server);
            rules.set(GameRules.PVP, pvpBefore, server);
            config.deathLockout.enabled = lockoutBefore;
            config.graves.enabled = gravesBefore;
            config.hide.gameMessages = hiddenBefore;
            TestPlayers.leave(killer);
            if (victim != null) {
                TestPlayers.leave(victim);
            }
            if (bystander != null) {
                TestPlayers.leave(bystander);
            }
        }
        helper.succeed();
    }

    // Every packet with text the server writes, and the text in items and entity data, moves the item a hover shows,
    // nested in another hover or in a translation's arguments too. Plain text goes out as the very packet.
    @GameTest(environment = TEXT)
    public void everyTextShiftsTheItemsItShows(GameTestHelper helper) {
        ServerPlayer player = OffsetTestKit.join(helper, "cttexts", GameType.CREATIVE);
        try {
            List<BlockPos> client = List.of(CoordinateOffsets.offset(player).shift(TARGET));
            Style hover = Style.EMPTY.withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(compass())));
            Component shown = Component.literal("Home").withStyle(hover);
            Component nested = Component.translatable("chat.type.text", Component.literal("Ann"),
                    Component.literal("look").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(shown))));
            ChatType.Bound bound = ChatType.bind(ChatType.CHAT, player.registryAccess(), shown);
            SignedMessageBody.Packed body = new SignedMessageBody.Packed("hello", Instant.now(), 5L, LastSeenMessages.Packed.EMPTY);
            ClientboundSystemChatPacket plain = new ClientboundSystemChatPacket(Component.literal("Nothing shown"), false);
            ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), shown, BossEvent.BossBarColor.BLUE,
                    BossEvent.BossBarOverlay.PROGRESS);
            ClientboundPlayerInfoUpdatePacket info = new ClientboundPlayerInfoUpdatePacket(
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), List.of());
            ((PlayerInfoUpdatePacketAccessor) info).yoltSetEntries(List.of(new ClientboundPlayerInfoUpdatePacket.Entry(
                    UUID.randomUUID(), null, true, 0, GameType.SURVIVAL, shown, true, 0, null)));
            ItemStack named = new ItemStack(Items.WRITTEN_BOOK);
            named.set(DataComponents.CUSTOM_NAME, shown);
            named.set(DataComponents.ITEM_NAME, shown);
            named.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("plain"), nested)));
            named.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Notes"), "Ann", 0,
                    List.of(new Filterable<>(shown, Optional.of(nested))), true));
            List<Component> lines = List.of(shown, Component.empty(), Component.empty(), Component.empty());
            named.set(DataComponents.SIGN_TEXT_FRONT, new SignText(lines, lines, DyeColor.BLACK, false));
            int mark = OffsetTestKit.mark(player);
            List<Packet<?>> sent = List.of(
                    new ClientboundSystemChatPacket(nested, false),
                    new ClientboundPlayerCombatKillPacket(7, shown),
                    new ClientboundDisguisedChatPacket(nested, bound),
                    new ClientboundPlayerChatPacket(1, player.getUUID(), 0, Optional.empty(), body, Optional.of(nested),
                            FilterMask.PASS_THROUGH, bound),
                    new ClientboundSetTitleTextPacket(shown),
                    new ClientboundSetSubtitleTextPacket(shown),
                    new ClientboundSetActionBarTextPacket(shown),
                    new ClientboundTabListPacket(shown, Component.literal("footer")),
                    ClientboundBossEventPacket.createAddPacket(bar),
                    ClientboundBossEventPacket.createUpdateNamePacket(bar),
                    info,
                    SetPlayerTeamPacketInvoker.create("ctteam", 0, Optional.of(new ClientboundSetPlayerTeamPacket.Parameters(shown,
                            shown, Component.empty(), Team.Visibility.ALWAYS, Team.CollisionRule.ALWAYS, Optional.empty(), (byte) 0)),
                            List.of("Ann")),
                    new ClientboundSetObjectivePacket(new Objective(new Scoreboard(), "ctscore", ObjectiveCriteria.DUMMY, shown,
                            ObjectiveCriteria.RenderType.INTEGER, false, new FixedFormat(shown)), 0),
                    new ClientboundSetScorePacket("Ann", "ctscore", 3, Optional.of(shown), Optional.of(new StyledFormat(hover))),
                    new ClientboundOpenScreenPacket(9, MenuType.GENERIC_9x3, shown),
                    new ClientboundMapItemDataPacket(new MapId(77), (byte) 0, false, Optional.of(List.of(new MapDecoration(
                            MapDecorationTypes.TARGET_X, (byte) 1, (byte) 2, (byte) 0, Optional.of(shown)))), Optional.empty()),
                    new ClientboundCommandSuggestionsPacket(3, 0, 1, List.of(
                            new ClientboundCommandSuggestionsPacket.Entry("home", Optional.of(shown)))),
                    new ClientboundTestInstanceBlockStatus(shown, Optional.empty()),
                    new ClientboundDisconnectPacket(shown),
                    new ClientboundServerDataPacket(shown, Optional.empty()),
                    new ClientboundResourcePackPushPacket(UUID.randomUUID(), "https://example.invalid/pack.zip", "", false,
                            Optional.of(shown)),
                    new ClientboundServerLinksPacket(List.of(
                            new ServerLinks.UntrustedEntry(Either.right(shown), "https://example.invalid"))),
                    new ClientboundSetEntityDataPacket(4_545, List.of(
                            new SynchedEntityData.DataValue<>(2, EntityDataSerializers.OPTIONAL_COMPONENT, Optional.of(shown)),
                            new SynchedEntityData.DataValue<>(23, EntityDataSerializers.COMPONENT, nested))),
                    new ClientboundContainerSetContentPacket(0, 1, List.of(named), ItemStack.EMPTY),
                    plain);
            sent.forEach(player.connection::send);
            List<Packet<?>> got = TestPlayers.sentPackets(player).subList(mark, mark + sent.size());

            helper.assertValueEqual(targets(((ClientboundSystemChatPacket) got.get(0)).content()), client, "system chat");
            helper.assertValueEqual(targets(((ClientboundPlayerCombatKillPacket) got.get(1)).message()), client, "death screen");
            ClientboundDisguisedChatPacket disguised = (ClientboundDisguisedChatPacket) got.get(2);
            helper.assertValueEqual(targets(disguised.message()), client, "disguised chat");
            helper.assertValueEqual(targets(disguised.chatType().name()), client, "disguised chat's sender name");
            ClientboundPlayerChatPacket chat = (ClientboundPlayerChatPacket) got.get(3);
            helper.assertValueEqual(targets(chat.unsignedContent().orElseThrow()), client, "player chat's unsigned form");
            helper.assertValueEqual(targets(chat.chatType().name()), client, "player chat's sender name");
            helper.assertTrue(chat.body() == body, "the signed body of a player chat was copied");
            helper.assertValueEqual(targets(((ClientboundSetTitleTextPacket) got.get(4)).text()), client, "title");
            helper.assertValueEqual(targets(((ClientboundSetSubtitleTextPacket) got.get(5)).text()), client, "subtitle");
            helper.assertValueEqual(targets(((ClientboundSetActionBarTextPacket) got.get(6)).text()), client, "action bar");
            ClientboundTabListPacket tab = (ClientboundTabListPacket) got.get(7);
            helper.assertValueEqual(targets(tab.header()), client, "tab list header");
            helper.assertValueEqual(tab.footer().getString(), "footer", "tab list footer");
            helper.assertValueEqual(bossBarNames((ClientboundBossEventPacket) got.get(8)), client, "boss bar added");
            helper.assertValueEqual(bossBarNames((ClientboundBossEventPacket) got.get(9)), client, "boss bar renamed");
            helper.assertValueEqual(targets(((ClientboundPlayerInfoUpdatePacket) got.get(10)).entries().getFirst().displayName()),
                    client, "tab list name");
            ClientboundSetPlayerTeamPacket team = (ClientboundSetPlayerTeamPacket) got.get(11);
            helper.assertValueEqual(targets(team.getParameters().orElseThrow().displayName()), client, "team name");
            helper.assertValueEqual(targets(team.getParameters().orElseThrow().playerPrefix()), client, "team prefix");
            helper.assertValueEqual(team.getTeamAction(), ClientboundSetPlayerTeamPacket.Action.ADD, "team action");
            helper.assertValueEqual(List.copyOf(team.getPlayers()), List.of("Ann"), "team members");
            ClientboundSetObjectivePacket objective = (ClientboundSetObjectivePacket) got.get(12);
            helper.assertValueEqual(targets(objective.getDisplayName()), client, "objective name");
            helper.assertValueEqual(targets(((FixedFormat) objective.getNumberFormat().orElseThrow()).value()), client,
                    "objective number");
            ClientboundSetScorePacket score = (ClientboundSetScorePacket) got.get(13);
            helper.assertValueEqual(targets(score.display().orElseThrow()), client, "score name");
            helper.assertValueEqual(targets(Component.empty().withStyle(((StyledFormat) score.numberFormat().orElseThrow()).style())),
                    client, "score number style");
            helper.assertValueEqual(targets(((ClientboundOpenScreenPacket) got.get(14)).getTitle()), client, "screen title");
            helper.assertValueEqual(targets(((ClientboundMapItemDataPacket) got.get(15)).decorations().orElseThrow().getFirst()
                    .name().orElseThrow()), client, "map marker name");
            helper.assertValueEqual(targets(((ClientboundCommandSuggestionsPacket) got.get(16)).suggestions().getFirst().tooltip()
                    .orElseThrow()), client, "suggestion tooltip");
            helper.assertValueEqual(targets(((ClientboundTestInstanceBlockStatus) got.get(17)).status()), client, "test status");
            helper.assertValueEqual(targets(((ClientboundDisconnectPacket) got.get(18)).reason()), client, "disconnect reason");
            helper.assertValueEqual(targets(((ClientboundServerDataPacket) got.get(19)).motd()), client, "server motd");
            helper.assertValueEqual(targets(((ClientboundResourcePackPushPacket) got.get(20)).prompt().orElseThrow()), client,
                    "resource pack prompt");
            helper.assertValueEqual(targets(((ClientboundServerLinksPacket) got.get(21)).links().getFirst().type().right()
                    .orElseThrow()), client, "server link name");
            List<SynchedEntityData.DataValue<?>> values = ((ClientboundSetEntityDataPacket) got.get(22)).packedItems();
            helper.assertValueEqual(targets(((Optional<?>) values.get(0).value()).map(Component.class::cast).orElseThrow()), client,
                    "entity's custom name");
            helper.assertValueEqual(targets((Component) values.get(1).value()), client, "text display's text");
            ItemStack item = ((ClientboundContainerSetContentPacket) got.get(23)).items().getFirst();
            helper.assertValueEqual(targets(item.get(DataComponents.CUSTOM_NAME)), client, "item's custom name");
            helper.assertValueEqual(targets(item.get(DataComponents.ITEM_NAME)), client, "item's name");
            helper.assertValueEqual(item.get(DataComponents.LORE).lines().get(0).getString(), "plain", "plain lore line");
            helper.assertValueEqual(targets(item.get(DataComponents.LORE).lines().get(1)), client, "lore line");
            Filterable<Component> page = item.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst();
            helper.assertValueEqual(targets(page.raw()), client, "book page");
            helper.assertValueEqual(targets(page.filtered().orElseThrow()), client, "filtered book page");
            helper.assertValueEqual(targets(item.get(DataComponents.SIGN_TEXT_FRONT).getMessages(false).getFirst()), client,
                    "sign item's line");
            helper.assertTrue(got.get(24) == plain, "plain text was copied");
            helper.assertValueEqual(targets(named.get(DataComponents.CUSTOM_NAME)), List.of(TARGET), "the server's own item name");
        } finally {
            TestPlayers.leave(player);
        }
        helper.succeed();
    }

    private static ItemStack compass() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(Level.OVERWORLD, TARGET)), true));
        return compass;
    }

    // The lodestone targets of the items the text shows, anywhere in it, in the order they come
    private static List<BlockPos> targets(Component text) {
        List<BlockPos> found = new ArrayList<>();
        collect(text, found);
        return found;
    }

    private static void collect(Component text, List<BlockPos> found) {
        HoverEvent hover = text.getStyle().getHoverEvent();
        if (hover instanceof HoverEvent.ShowItem item) {
            LodestoneTracker tracker = item.item().get(DataComponents.LODESTONE_TRACKER);
            if (tracker != null) {
                tracker.target().ifPresent(target -> found.add(target.pos()));
            }
        } else if (hover instanceof HoverEvent.ShowText shown) {
            collect(shown.value(), found);
        }
        if (text.getContents() instanceof TranslatableContents translatable) {
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component component) {
                    collect(component, found);
                }
            }
        }
        text.getSiblings().forEach(sibling -> collect(sibling, found));
    }

    private static List<BlockPos> bossBarNames(ClientboundBossEventPacket packet) {
        List<BlockPos> found = new ArrayList<>();
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                            boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                collect(name, found);
            }

            @Override
            public void updateName(UUID id, Component name) {
                collect(name, found);
            }
        });
        return found;
    }
}
