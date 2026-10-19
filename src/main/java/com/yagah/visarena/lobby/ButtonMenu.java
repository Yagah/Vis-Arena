package com.yagah.visarena.lobby;

import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;

/**
 * A vanilla chest screen used as a menu: its items are buttons. Nothing can be taken or moved, in it or in the player's
 * own inventory below it; a left or right click on a button runs its action. The client predicts the item moving, so
 * every click resends the real contents.
 */
public class ButtonMenu extends ChestMenu {
    private static final List<MenuType<ChestMenu>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
            MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

    private final int size;
    private final IntConsumer onClick;

    /** A menu of the given rows (1 to 6); onClick receives the clicked slot, counted from the top left. */
    public ButtonMenu(int containerId, Inventory inventory, Container buttons, int rows, IntConsumer onClick) {
        super(TYPES.get(rows - 1), containerId, inventory, buttons, rows);
        this.size = rows * 9;
        this.onClick = onClick;
    }

    /** An empty grid of the given rows, filled with blank grey panes. */
    public static SimpleContainer grid(int rows) {
        SimpleContainer container = new SimpleContainer(rows * 9);
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, button(Items.GRAY_STAINED_GLASS_PANE, Component.literal(" "), List.of()));
        }
        return container;
    }

    /** A button: an item with a name and lore lines, no italics, and no vanilla tooltip lines (attack damage and such). */
    public static ItemStack button(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(style -> style.withItalic(false)));
        // Lore lines are grey unless they set their own colour.
        stack.set(DataComponents.LORE, new ItemLore(lore.stream()
                .map(line -> (Component) line.copy().withStyle(style -> style.withItalic(false)
                        .withColor(style.getColor() != null ? style.getColor() : TextColor.fromLegacyFormat(ChatFormatting.GRAY))))
                .toList()));
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY.withTooltip(false));
        stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
        return stack;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < size && clickType == ClickType.PICKUP) {
            onClick.accept(slotId);
        }
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
