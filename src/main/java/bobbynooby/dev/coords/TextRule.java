package bobbynooby.dev.coords;

import bobbynooby.dev.coords.CoordinateOffsets.Context;
import bobbynooby.dev.mixin.PlayerInfoUpdatePacketAccessor;
import bobbynooby.dev.mixin.SetPlayerTeamPacketInvoker;
import com.mojang.datafixers.util.Either;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundServerDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Items inside text: a hover that shows an item carries the whole item, such as the named compass a killer held, which
 * a death message shows. The words themselves are left as they are, coordinates typed into them included.
 */
public final class TextRule {
    // The packet's methods for a team that is added and one that changes
    private static final int TEAM_ADD = 0;
    private static final int TEAM_CHANGE = 2;

    private TextRule() {}

    static void register() {
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT, (packet, context) -> {
            Component content = shift(packet.content(), context.offset());
            return content == packet.content() ? packet : new ClientboundSystemChatPacket(content, packet.overlay());
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_KILL, (packet, context) -> {
            Component message = shift(packet.message(), context.offset());
            return message == packet.message() ? packet : new ClientboundPlayerCombatKillPacket(packet.playerId(), message);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_DISGUISED_CHAT, TextRule::disguisedChat);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_PLAYER_CHAT, TextRule::playerChat);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_TITLE_TEXT, (packet, context) -> {
            Component text = shift(packet.text(), context.offset());
            return text == packet.text() ? packet : new ClientboundSetTitleTextPacket(text);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_SUBTITLE_TEXT, (packet, context) -> {
            Component text = shift(packet.text(), context.offset());
            return text == packet.text() ? packet : new ClientboundSetSubtitleTextPacket(text);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_ACTION_BAR_TEXT, (packet, context) -> {
            Component text = shift(packet.text(), context.offset());
            return text == packet.text() ? packet : new ClientboundSetActionBarTextPacket(text);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_TAB_LIST, (packet, context) -> {
            Component header = shift(packet.header(), context.offset());
            Component footer = shift(packet.footer(), context.offset());
            return header == packet.header() && footer == packet.footer() ? packet : new ClientboundTabListPacket(header, footer);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_BOSS_EVENT, TextRule::bossBar);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE, TextRule::playerInfo);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_PLAYER_TEAM, TextRule::team);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_OBJECTIVE, TextRule::objective);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SET_SCORE, TextRule::score);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_OPEN_SCREEN, (packet, context) -> {
            Component title = shift(packet.getTitle(), context.offset());
            return title == packet.getTitle() ? packet
                    : new ClientboundOpenScreenPacket(packet.getContainerId(), packet.getType(), title);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_MAP_ITEM_DATA, TextRule::mapData);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_COMMAND_SUGGESTIONS, TextRule::suggestions);
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_TEST_INSTANCE_BLOCK_STATUS, (packet, context) -> {
            Component status = shift(packet.status(), context.offset());
            return status == packet.status() ? packet : new ClientboundTestInstanceBlockStatus(status, packet.size());
        });
        Outgoing.translate(CommonPacketTypes.CLIENTBOUND_DISCONNECT, (packet, context) -> {
            Component reason = shift(packet.reason(), context.offset());
            return reason == packet.reason() ? packet : new ClientboundDisconnectPacket(reason);
        });
        Outgoing.translate(GamePacketTypes.CLIENTBOUND_SERVER_DATA, (packet, context) -> {
            Component motd = shift(packet.motd(), context.offset());
            return motd == packet.motd() ? packet : new ClientboundServerDataPacket(motd, packet.iconBytes());
        });
        Outgoing.translate(CommonPacketTypes.CLIENTBOUND_RESOURCE_PACK_PUSH, (packet, context) -> {
            Optional<Component> prompt = shift(packet.prompt(), context.offset());
            return prompt == packet.prompt() ? packet
                    : new ClientboundResourcePackPushPacket(packet.id(), packet.url(), packet.hash(), packet.required(), prompt);
        });
        Outgoing.translate(CommonPacketTypes.CLIENTBOUND_SERVER_LINKS, TextRule::serverLinks);
    }

    /** The text as the client is sent it: the very text when no item shown in it holds a position. */
    public static Component shift(Component text, Offset offset) {
        if (offset.isZero()) {
            return text;
        }
        ComponentContents contents = contents(text.getContents(), offset);
        Style style = style(text.getStyle(), offset);
        List<Component> siblings = CoordinateOffsets.mapEach(text.getSiblings(), sibling -> shift(sibling, offset));
        if (contents == text.getContents() && style == text.getStyle() && siblings == text.getSiblings()) {
            return text;
        }
        MutableComponent copy = MutableComponent.create(contents).setStyle(style);
        siblings.forEach(copy::append);
        return copy;
    }

    public static Optional<Component> shift(Optional<Component> text, Offset offset) {
        if (text.isEmpty()) {
            return text;
        }
        Component shifted = shift(text.get(), offset);
        return shifted == text.get() ? text : Optional.of(shifted);
    }

    /** The style's hover as the client is sent it: the very style when nothing in it moves. */
    public static Style style(Style style, Offset offset) {
        HoverEvent hover = style.getHoverEvent();
        if (hover == null) {
            return style;
        }
        HoverEvent shifted = switch (hover) {
            case HoverEvent.ShowItem item -> {
                ItemStackTemplate moved = ItemRule.shift(item.item(), offset);
                yield moved == item.item() ? hover : new HoverEvent.ShowItem(moved);
            }
            case HoverEvent.ShowText shown -> {
                Component moved = shift(shown.value(), offset);
                yield moved == shown.value() ? hover : new HoverEvent.ShowText(moved);
            }
            case HoverEvent.ShowEntity entity -> {
                HoverEvent.EntityTooltipInfo info = entity.entity();
                Optional<Component> name = shift(info.name, offset);
                yield name == info.name ? hover : new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(info.type, info.uuid, name));
            }
            default -> hover;
        };
        return shifted == hover ? style : style.withHoverEvent(shifted);
    }

    // A translated message's arguments are texts of their own, such as the names in a death message
    private static ComponentContents contents(ComponentContents contents, Offset offset) {
        if (!(contents instanceof TranslatableContents translatable)) {
            return contents;
        }
        Object[] args = translatable.getArgs();
        Object[] shifted = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Component arg) {
                Component moved = shift(arg, offset);
                if (moved != arg) {
                    if (shifted == null) {
                        shifted = args.clone();
                    }
                    shifted[i] = moved;
                }
            }
        }
        return shifted == null ? contents : new TranslatableContents(translatable.getKey(), translatable.getFallback(), shifted);
    }

    private static ChatType.Bound chatType(ChatType.Bound bound, Offset offset) {
        Component name = shift(bound.name(), offset);
        Optional<Component> target = shift(bound.targetName(), offset);
        return name == bound.name() && target == bound.targetName() ? bound : new ChatType.Bound(bound.chatType(), name, target);
    }

    private static Packet<?> disguisedChat(ClientboundDisguisedChatPacket packet, Context context) {
        Component message = shift(packet.message(), context.offset());
        ChatType.Bound chatType = chatType(packet.chatType(), context.offset());
        return message == packet.message() && chatType == packet.chatType() ? packet
                : new ClientboundDisguisedChatPacket(message, chatType);
    }

    // The signed body is the sender's own words, which the client checks against the signature, so only what the server
    // adds is looked at: the unsigned form and the names the chat type decorates it with
    private static Packet<?> playerChat(ClientboundPlayerChatPacket packet, Context context) {
        Optional<Component> unsigned = shift(packet.unsignedContent(), context.offset());
        ChatType.Bound chatType = chatType(packet.chatType(), context.offset());
        return unsigned == packet.unsignedContent() && chatType == packet.chatType() ? packet
                : new ClientboundPlayerChatPacket(packet.globalIndex(), packet.sender(), packet.index(), packet.signature(),
                        packet.body(), unsigned, packet.filterMask(), chatType);
    }

    // Only adding a bar and renaming it carry a name. The packet is built from a bar of its own with the same settings.
    private static Packet<?> bossBar(ClientboundBossEventPacket packet, Context context) {
        Offset offset = context.offset();
        List<Packet<?>> shifted = new ArrayList<>(1);
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                            boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
                Component moved = shift(name, offset);
                if (moved != name) {
                    BossEvent bar = new BossEvent(id, moved, color, overlay) {};
                    bar.setProgress(progress);
                    bar.setDarkenScreen(darkenScreen).setPlayBossMusic(playMusic).setCreateWorldFog(createWorldFog);
                    shifted.add(ClientboundBossEventPacket.createAddPacket(bar));
                }
            }

            @Override
            public void updateName(UUID id, Component name) {
                Component moved = shift(name, offset);
                if (moved != name) {
                    shifted.add(ClientboundBossEventPacket.createUpdateNamePacket(
                            new BossEvent(id, moved, BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.PROGRESS) {}));
                }
            }
        });
        return shifted.isEmpty() ? packet : shifted.getFirst();
    }

    private static Packet<?> playerInfo(ClientboundPlayerInfoUpdatePacket packet, Context context) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = CoordinateOffsets.mapEach(packet.entries(), entry -> {
            if (entry.displayName() == null) {
                return entry;
            }
            Component name = shift(entry.displayName(), context.offset());
            return name == entry.displayName() ? entry : new ClientboundPlayerInfoUpdatePacket.Entry(entry.profileId(),
                    entry.profile(), entry.listed(), entry.latency(), entry.gameMode(), name, entry.showHat(), entry.listOrder(),
                    entry.chatSession());
        });
        if (entries == packet.entries()) {
            return packet;
        }
        ClientboundPlayerInfoUpdatePacket copy = new ClientboundPlayerInfoUpdatePacket(packet.actions(), List.of());
        ((PlayerInfoUpdatePacketAccessor) copy).yoltSetEntries(entries);
        return copy;
    }

    // Only adding a team and changing it carry its texts
    private static Packet<?> team(ClientboundSetPlayerTeamPacket packet, Context context) {
        if (packet.getParameters().isEmpty()) {
            return packet;
        }
        Offset offset = context.offset();
        ClientboundSetPlayerTeamPacket.Parameters parameters = packet.getParameters().get();
        Component displayName = shift(parameters.displayName(), offset);
        Component prefix = shift(parameters.playerPrefix(), offset);
        Component suffix = shift(parameters.playerSuffix(), offset);
        if (displayName == parameters.displayName() && prefix == parameters.playerPrefix() && suffix == parameters.playerSuffix()) {
            return packet;
        }
        int method = packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD ? TEAM_ADD : TEAM_CHANGE;
        return SetPlayerTeamPacketInvoker.create(packet.getName(), method, Optional.of(new ClientboundSetPlayerTeamPacket.Parameters(
                displayName, prefix, suffix, parameters.nameTagVisibility(), parameters.collisionRule(), parameters.color(),
                parameters.options())), packet.getPlayers());
    }

    // Built from an objective of its own, as the packet has no other constructor
    private static Packet<?> objective(ClientboundSetObjectivePacket packet, Context context) {
        Offset offset = context.offset();
        Component displayName = shift(packet.getDisplayName(), offset);
        Optional<NumberFormat> format = numberFormat(packet.getNumberFormat(), offset);
        if (displayName == packet.getDisplayName() && format == packet.getNumberFormat()) {
            return packet;
        }
        return new ClientboundSetObjectivePacket(new Objective(new Scoreboard(), packet.getObjectiveName(), ObjectiveCriteria.DUMMY,
                displayName, packet.getRenderType(), false, format.orElse(null)), packet.getMethod());
    }

    private static Packet<?> score(ClientboundSetScorePacket packet, Context context) {
        Offset offset = context.offset();
        Optional<Component> display = shift(packet.display(), offset);
        Optional<NumberFormat> format = numberFormat(packet.numberFormat(), offset);
        return display == packet.display() && format == packet.numberFormat() ? packet
                : new ClientboundSetScorePacket(packet.owner(), packet.objectiveName(), packet.score(), display, format);
    }

    // A fixed number shows a text of its own, and a styled one a style that can hold a hover
    private static Optional<NumberFormat> numberFormat(Optional<NumberFormat> format, Offset offset) {
        if (format.isEmpty()) {
            return format;
        }
        NumberFormat shifted = switch (format.get()) {
            case FixedFormat fixed -> {
                Component value = shift(fixed.value(), offset);
                yield value == fixed.value() ? fixed : new FixedFormat(value);
            }
            case StyledFormat styled -> {
                Style moved = style(styled.style(), offset);
                yield moved == styled.style() ? styled : new StyledFormat(moved);
            }
            default -> format.get();
        };
        return shifted == format.get() ? format : Optional.of(shifted);
    }

    // A map's markers are placed on the map itself, so only a named marker's name is looked at
    private static Packet<?> mapData(ClientboundMapItemDataPacket packet, Context context) {
        if (packet.decorations().isEmpty()) {
            return packet;
        }
        List<MapDecoration> decorations = packet.decorations().get();
        List<MapDecoration> shifted = CoordinateOffsets.mapEach(decorations, decoration -> {
            Optional<Component> name = shift(decoration.name(), context.offset());
            return name == decoration.name() ? decoration
                    : new MapDecoration(decoration.type(), decoration.x(), decoration.y(), decoration.rot(), name);
        });
        return shifted == decorations ? packet : new ClientboundMapItemDataPacket(packet.mapId(), packet.scale(), packet.locked(),
                Optional.of(shifted), packet.colorPatch());
    }

    private static Packet<?> suggestions(ClientboundCommandSuggestionsPacket packet, Context context) {
        List<ClientboundCommandSuggestionsPacket.Entry> entries = CoordinateOffsets.mapEach(packet.suggestions(), entry -> {
            Optional<Component> tooltip = shift(entry.tooltip(), context.offset());
            return tooltip == entry.tooltip() ? entry : new ClientboundCommandSuggestionsPacket.Entry(entry.text(), tooltip);
        });
        return entries == packet.suggestions() ? packet
                : new ClientboundCommandSuggestionsPacket(packet.id(), packet.start(), packet.length(), entries);
    }

    // A link is either a known kind or one with a name of its own
    private static Packet<?> serverLinks(ClientboundServerLinksPacket packet, Context context) {
        List<ServerLinks.UntrustedEntry> links = CoordinateOffsets.mapEach(packet.links(), link -> {
            Component name = link.type().right().orElse(null);
            if (name == null) {
                return link;
            }
            Component moved = shift(name, context.offset());
            return moved == name ? link : new ServerLinks.UntrustedEntry(Either.right(moved), link.link());
        });
        return links == packet.links() ? packet : new ClientboundServerLinksPacket(links);
    }
}
