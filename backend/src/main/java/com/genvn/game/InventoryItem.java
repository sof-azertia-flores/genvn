package com.genvn.game;

public class InventoryItem {
    public String name;
    public String description;
    public String acquiredAtScene;

    public InventoryItem() {}

    public InventoryItem(String name, String description, String acquiredAtScene) {
        this.name = name;
        this.description = description;
        this.acquiredAtScene = acquiredAtScene;
    }
}
