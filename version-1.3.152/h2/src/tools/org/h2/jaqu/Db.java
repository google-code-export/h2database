/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.jaqu;

//## Java 1.5 begin ##
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.h2.jaqu.DbUpgrader.DefaultDbUpgrader;
import org.h2.jaqu.SQLDialect.DefaultSQLDialect;
import org.h2.jaqu.Table.JQDatabase;
import org.h2.jaqu.Table.JQTable;
import org.h2.jaqu.util.JdbcUtils;
import org.h2.jaqu.util.StringUtils;
import org.h2.jaqu.util.Utils;
import org.h2.jaqu.util.WeakIdentityHashMap;
//## Java 1.5 end ##

/**
 * This class represents a connection to a database.
 */
//## Java 1.5 begin ##
public class Db {

    /**
     * This map It holds unique tokens that are generated by functions such as
     * Function.sum(..) in "db.from(p).select(Function.sum(p.unitPrice))". It
     * doesn't actually hold column tokens, as those are bound to the query
     * itself.
     */
    private static final Map<Object, Token> TOKENS =
        Collections.synchronizedMap(new WeakIdentityHashMap<Object, Token>());

    private final Connection conn;
    private final Map<Class<?>, TableDefinition<?>> classMap =
        Utils.newHashMap();
    private final SQLDialect dialect;
    private DbUpgrader dbUpgrader = new DefaultDbUpgrader();
    private final Set<Class<?>> upgradeChecked = Collections.synchronizedSet(new HashSet<Class<?>>());

    private int todoDocumentNewFeaturesInHtmlFile;

    public Db(Connection conn) {
        this.conn = conn;
        dialect = getDialect(conn.getClass().getCanonicalName());
    }

    private SQLDialect getDialect(String clazz) {
    int todo;
        // TODO add special cases here
        return new DefaultSQLDialect();
    }

    static <X> X registerToken(X x, Token token) {
        TOKENS.put(x, token);
        return x;
    }

    static Token getToken(Object x) {
        return TOKENS.get(x);
    }

    private static <T> T instance(Class<T> clazz) {
        try {
            return clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Db open(String url, String user, String password) {
        try {
            Connection conn = JdbcUtils.getConnection(null, url, user, password);
            return new Db(conn);
        } catch (SQLException e) {
            throw convert(e);
        }
    }

    /**
     * Create a new database instance using a data source. This method is fast,
     * so that you can always call open() / close() on usage.
     *
     * @param ds the data source
     * @return the database instance.
     */
    public static Db open(DataSource ds) {
        try {
            return new Db(ds.getConnection());
        } catch (SQLException e) {
            throw convert(e);
        }
    }

    public static Db open(String url, String user, char[] password) {
        try {
            Properties prop = new Properties();
            prop.setProperty("user", user);
            prop.put("password", password);
            Connection conn = JdbcUtils.getConnection(null, url, prop);
            return new Db(conn);
        } catch (SQLException e) {
            throw convert(e);
        }
    }

    private static Error convert(Exception e) {
        return new Error(e);
    }

    public <T> void insert(T t) {
        Class<?> clazz = t.getClass();
        define(clazz).createTableIfRequired(this).insert(this, t, false);
    }

    public <T> long insertAndGetKey(T t) {
        Class<?> clazz = t.getClass();
        return define(clazz).createTableIfRequired(this).insert(this, t, true);
    }

    public <T> void merge(T t) {
        Class<?> clazz = t.getClass();
        define(clazz).createTableIfRequired(this).merge(this, t);
    }

    public <T> void update(T t) {
        Class<?> clazz = t.getClass();
        define(clazz).createTableIfRequired(this).update(this, t);
    }

    public <T> void delete(T t) {
        Class<?> clazz = t.getClass();
        define(clazz).createTableIfRequired(this).delete(this, t);
    }

    public <T extends Object> Query<T> from(T alias) {
        Class<?> clazz = alias.getClass();
        define(clazz).createTableIfRequired(this);
        return Query.from(this, alias);
    }

    Db upgradeDb() {
        if (!upgradeChecked.contains(dbUpgrader.getClass())) {
            // Flag as checked immediately because calls are nested.
            upgradeChecked.add(dbUpgrader.getClass());

            JQDatabase model = dbUpgrader.getClass().getAnnotation(JQDatabase.class);
            if (model.version() > 0) {
                DbVersion v = new DbVersion();
                DbVersion dbVersion =
                    // (SCHEMA="" && TABLE="") == DATABASE
                    from(v).where(v.schema).is("").and(v.table).is("").selectFirst();
                if (dbVersion == null) {
                    // Database has no version registration, but model specifies
                    // version. Insert DbVersion entry and return.
                    DbVersion newDb = new DbVersion(model.version());
                    insert(newDb);
                } else {
                    // Database has a version registration,
                    // check to see if upgrade is required.
                    if ((model.version() > dbVersion.version)
                            && (dbUpgrader != null)) {
                        // Database is an older version than model.
                        boolean success = dbUpgrader.upgradeDatabase(this,
                                dbVersion.version, model.version());
                        if (success) {
                            dbVersion.version = model.version();
                            update(dbVersion);
                        }
                    }
                }
            }
        }
        return this;
    }

    <T> void upgradeTable(TableDefinition<T> model) {
        if (!upgradeChecked.contains(model.getModelClass())) {
            // Flag as checked immediately because calls are nested.
            upgradeChecked.add(model.getModelClass());

            if (model.tableVersion > 0) {
                // Table is using JaQu version tracking.
                DbVersion v = new DbVersion();
                String schema = StringUtils.isNullOrEmpty(model.schemaName) ? "" : model.schemaName;
                DbVersion dbVersion =
                    from(v).where(v.schema).like(schema).and(v.table)
                    .like(model.tableName).selectFirst();
                if (dbVersion == null) {
                    // Table has no version registration, but model specifies
                    // version. Insert DbVersion entry and return.
                    DbVersion newTable = new DbVersion(model.tableVersion);
                    newTable.schema = schema;
                    newTable.table = model.tableName;
                    insert(newTable);
                } else {
                    // Table has a version registration.
                    // Check to see if upgrade is required.
                    if ((model.tableVersion > dbVersion.version)
                            && (dbUpgrader != null)) {
                        // Table is an older version than model.
                        boolean success = dbUpgrader.upgradeTable(this, schema,
                                model.tableName, dbVersion.version, model.tableVersion);
                        if (success) {
                            dbVersion.version = model.tableVersion;
                            update(dbVersion);
                        }
                    }
                }
            }
        }
    }

    <T> TableDefinition<T> define(Class<T> clazz) {
        TableDefinition<T> def = getTableDefinition(clazz);
        if (def == null) {
            upgradeDb();
            def = new TableDefinition<T>(clazz);
            def.mapFields();
            classMap.put(clazz, def);
            if (Table.class.isAssignableFrom(clazz)) {
                T t = instance(clazz);
                Table table = (Table) t;
                Define.define(def, table);
            } else if (clazz.isAnnotationPresent(JQTable.class)) {
                // Annotated Class skips Define().define() static initializer
                T t = instance(clazz);
                def.mapObject(t);
            }
        }
        return def;
    }

    public synchronized void setDbUpgrader(DbUpgrader upgrader) {
        if (!upgrader.getClass().isAnnotationPresent(JQDatabase.class)) {
            throw new RuntimeException("DbUpgrader must be annotated with "
                    + JQDatabase.class.getSimpleName());
        }
        this.dbUpgrader = upgrader;
        upgradeChecked.clear();
    }

    SQLDialect getDialect() {
        return dialect;
    }

    public Connection getConnection() {
        return conn;
    }

    public void close() {
        try {
            conn.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <A> TestCondition<A> test(A x) {
        return new TestCondition<A>(x);
    }

    public <T> void insertAll(List<T> list) {
        for (T t : list) {
            insert(t);
        }
    }

    public <T> List<Long> insertAllAndGetKeys(List<T> list) {
        List<Long> identities = new ArrayList<Long>();
        for (T t : list) {
            identities.add(insertAndGetKey(t));
        }
        return identities;
    }

    public <T> void updateAll(List<T> list) {
        for (T t : list) {
            update(t);
        }
    }

    public <T> void deleteAll(List<T> list) {
        for (T t : list) {
            delete(t);
        }
    }

    PreparedStatement prepare(String sql, boolean returnGeneratedKeys) {
        try {
            if (returnGeneratedKeys) {
                return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            }
            return conn.prepareStatement(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    <T> TableDefinition<T> getTableDefinition(Class<T> clazz) {
        return (TableDefinition<T>) classMap.get(clazz);
    }

    /**
     * Run a SQL query directly against the database.
     *
     * @param sql the SQL statement
     * @return the result set
     */
    public ResultSet executeQuery(String sql) {
        try {
            return conn.createStatement().executeQuery(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Run a SQL statement directly against the database.
     *
     * @param sql the SQL statement
     * @return the update count
     */
    public int executeUpdate(String sql) {
        try {
            Statement stat = conn.createStatement();
            int updateCount = stat.executeUpdate(sql);
            stat.close();
            return updateCount;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

//    <X> FieldDefinition<X> getFieldDefinition(X x) {
//        return aliasMap.get(x).getFieldDefinition();
//    }
//
//    <X> SelectColumn<X> getSelectColumn(X x) {
//        return aliasMap.get(x);
//    }

}
//## Java 1.5 end ##
