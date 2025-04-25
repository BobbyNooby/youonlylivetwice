package dev.bobbynooby.youOnlyLiveTwice.utils;

import dev.bobbynooby.youOnlyLiveTwice.npc.RegistryHandler;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.UUID;

public class LocalDatabase {

    private final Connection connection;

    public LocalDatabase(String path) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      alive BOOL NOT NULL DEFAULT true,
                      npcUUID TEXT NOT NULL
                      );
                    """);
        }
    }


    public void closeConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    public boolean playerExists(Player player) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            preparedStatement.setString(1, player.getUniqueId().toString());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next();
            }
        }
    }


    public boolean playerIsAlive(Player player) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ? AND alive = true ")) {
            preparedStatement.setString(1, player.getUniqueId().toString());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next();
            }
        }
    }


    public void addPlayer(Player player, UUID npcUUID) {
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO players (uuid, name, alive, npcUUID) VALUES (?, ?, ?, ?)")) {
            preparedStatement.setString(1, player.getUniqueId().toString());
            preparedStatement.setString(2, player.getName());
            preparedStatement.setBoolean(3, true);
            preparedStatement.setString(4, npcUUID.toString());
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            PluginPrint.println("Error in adding player" + e.getMessage());
        }
    }

    public void deletePlayer(UUID uuid) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM players WHERE uuid = ?")) {
            preparedStatement.setString(1, uuid.toString());
            preparedStatement.executeUpdate();
        }
    }

    public void killPlayer(UUID uuid) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("UPDATE players SET alive = false WHERE uuid = ?")) {
            preparedStatement.setString(1, uuid.toString());
            preparedStatement.executeUpdate();
        }
    }


    public void revivePlayer(UUID uuid) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("UPDATE players SET alive = true WHERE uuid = ?")) {
            preparedStatement.setString(1, uuid.toString());
            preparedStatement.executeUpdate();
        }
    }


    public void toggleAliveStatus(Player player) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("UPDATE players SET alive = NOT alive WHERE uuid = ?")) {
            preparedStatement.setString(1, player.getUniqueId().toString());
            preparedStatement.executeUpdate();
        }
    }

    public UUID getUUIDFromUsername(String username) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT uuid FROM players WHERE name = ?")) {
            preparedStatement.setString(1, username);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return UUID.fromString(resultSet.getString("uuid"));
                } else {
                    return null;
                }
            }
        }
    }

    public UUID getPlayerUUIDFromNPCUUID(UUID npcUUID) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT uuid FROM players WHERE npcUUID = ?")) {
            preparedStatement.setString(1, npcUUID.toString());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return UUID.fromString(resultSet.getString("uuid"));
                } else {
                    return null;
                }
            }
        }
    }

    public UUID getNPCUUIDFromPlayerUUID(UUID playerUUID) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT npcUUID FROM players WHERE uuid = ?")) {
            preparedStatement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return UUID.fromString(resultSet.getString("npcUUID"));
                } else {
                    return null;
                }
            }
        }
    }

}
