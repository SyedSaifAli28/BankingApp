package test;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankManagementTest {

    @Test
    void createAccountRejectsBlankOrZeroValues() {
        assertFalse(BankManagement.createAccount("", 1234));
        assertFalse(BankManagement.createAccount("Alice", 0));
    }

    @Test
    void createAccountReturnsFalseOnDuplicateUsername() {
        FakeDatabase fakeDatabase = new FakeDatabase(1000, 2000);
        fakeDatabase.executeUpdateFailure = new SQLIntegrityConstraintViolationException("duplicate");
        BankManagement.con = fakeDatabase.connection;

        assertFalse(BankManagement.createAccount("Alice", 1234));
    }

    @Test
    void transferMoneyRejectsMissingReceiverOrAmount() {
        assertFalse(BankManagement.transferMoney(1, 0, 250));
        assertFalse(BankManagement.transferMoney(1, 2, 0));
    }

    @Test
    void transferMoneyCommitsWhenBalanceIsSufficient() {
        FakeDatabase fakeDatabase = new FakeDatabase(1000, 2000);
        BankManagement.con = fakeDatabase.connection;

        assertTrue(BankManagement.transferMoney(1, 2, 300));
        assertEquals(700, fakeDatabase.balances.get(1));
        assertEquals(2300, fakeDatabase.balances.get(2));
        assertTrue(fakeDatabase.committed.get());
        assertFalse(fakeDatabase.rolledBack.get());
    }

    @Test
    void transferMoneyRollsBackWhenBalanceIsInsufficient() {
        FakeDatabase fakeDatabase = new FakeDatabase(150, 2000);
        BankManagement.con = fakeDatabase.connection;

        assertFalse(BankManagement.transferMoney(1, 2, 250));
        assertEquals(150, fakeDatabase.balances.get(1));
        assertEquals(2000, fakeDatabase.balances.get(2));
        assertFalse(fakeDatabase.committed.get());
        assertTrue(fakeDatabase.rolledBack.get());
    }

    private static final class FakeDatabase {
        private final Map<Integer, Integer> balances;
        private final AtomicBoolean committed = new AtomicBoolean(false);
        private final AtomicBoolean rolledBack = new AtomicBoolean(false);
        private final Connection connection;
        private SQLException executeUpdateFailure;

        private FakeDatabase(int senderBalance, int receiverBalance) {
            this.balances = new HashMap<>();
            this.balances.put(1, senderBalance);
            this.balances.put(2, receiverBalance);
            this.connection = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> handleConnection(method.getName(), args, this)
            );
        }

        private Object handleConnection(String methodName, Object[] args, FakeDatabase fakeDatabase) throws SQLException {
            return switch (methodName) {
                case "prepareStatement" -> createPreparedStatement((String) args[0], fakeDatabase);
                case "commit" -> {
                    fakeDatabase.committed.set(true);
                    yield null;
                }
                case "rollback" -> {
                    fakeDatabase.rolledBack.set(true);
                    yield null;
                }
                case "setAutoCommit" -> null;
                case "close" -> null;
                case "isClosed" -> false;
                case "getAutoCommit" -> true;
                default -> null;
            };
        }

        private PreparedStatement createPreparedStatement(String sql, FakeDatabase fakeDatabase) {
            Map<Integer, Object> parameters = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setInt" -> {
                            parameters.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "setString" -> {
                            parameters.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "executeUpdate" -> handleUpdate(sql, parameters, fakeDatabase);
                        case "executeQuery" -> handleQuery(sql, parameters, fakeDatabase);
                        case "close" -> null;
                        default -> null;
                    }
            );
        }

        private Object handleUpdate(String sql, Map<Integer, Object> parameters, FakeDatabase fakeDatabase) throws SQLException {
            if (sql.contains("INSERT INTO customer")) {
                if (fakeDatabase.executeUpdateFailure != null) {
                    throw fakeDatabase.executeUpdateFailure;
                }
                return 1;
            }

            if (sql.contains("UPDATE customer SET balance = balance - ?")) {
                int amount = (Integer) parameters.get(1);
                int senderAc = (Integer) parameters.get(2);
                fakeDatabase.balances.put(senderAc, fakeDatabase.balances.get(senderAc) - amount);
                return 1;
            }

            if (sql.contains("UPDATE customer SET balance = balance + ?")) {
                int amount = (Integer) parameters.get(1);
                int receiverAc = (Integer) parameters.get(2);
                fakeDatabase.balances.put(receiverAc, fakeDatabase.balances.get(receiverAc) + amount);
                return 1;
            }

            return 1;
        }

        private ResultSet handleQuery(String sql, Map<Integer, Object> parameters, FakeDatabase fakeDatabase) {
            if (sql.contains("SELECT balance FROM customer WHERE ac_no = ?")) {
                int senderAc = (Integer) parameters.get(1);
                int balance = fakeDatabase.balances.getOrDefault(senderAc, 0);
                return resultSetWithValue(balance);
            }

            if (sql.contains("SELECT * FROM customer WHERE cname = ? AND pass_code = ?")) {
                return resultSetWithValue(0);
            }

            return resultSetWithValue(0);
        }

        private ResultSet resultSetWithValue(int balance) {
            AtomicBoolean rowAvailable = new AtomicBoolean(true);
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> rowAvailable.getAndSet(false);
                        case "getInt" -> {
                            String columnName = (String) args[0];
                            if ("balance".equals(columnName)) {
                                yield balance;
                            }
                            if ("ac_no".equals(columnName)) {
                                yield 1;
                            }
                            yield 0;
                        }
                        case "getString" -> "";
                        case "close" -> null;
                        default -> null;
                    }
            );
        }
    }
}
