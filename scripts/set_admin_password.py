import sqlite3, base64, hashlib, secrets, os

db=r'C:\Users\Kanbara\Desktop\T08\server\plugins\WebShopX\webshopx.db'
print('DB exists:', os.path.exists(db))
conn=sqlite3.connect(db)
cur=conn.cursor()
cur.execute("SELECT name, sql FROM sqlite_master WHERE type='table'")
for row in cur.fetchall():
    print('TABLE:', row[0])
# compute salt/hash
password='admin123456'
salt=secrets.token_bytes(16)
hashv=hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, 65536, dklen=32)
salt_b64=base64.b64encode(salt).decode('ascii')
hash_b64=base64.b64encode(hashv).decode('ascii')
# upsert admin user
cur.execute("SELECT id FROM web_users WHERE username = ?", ('admin',))
row=cur.fetchone()
if row:
    uid=row[0]
    cur.execute("UPDATE web_users SET password_hash=?, password_salt=?, auth_state='ACTIVE' WHERE id=?", (hash_b64, salt_b64, uid))
    print('Updated user id', uid)
else:
    cur.execute("INSERT INTO web_users (username, password_hash, password_salt, auth_state, bound_uuid) VALUES (?,?,?,?,?)", ('admin', hash_b64, salt_b64, 'ACTIVE', None))
    uid=cur.lastrowid
    print('Inserted user id', uid)
# ensure wallet exists if table present
try:
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='web_wallets'")
    if cur.fetchone():
        cur.execute("INSERT OR IGNORE INTO web_wallets (user_id, balance) VALUES (?,?)", (uid, 0))
        print('Ensured wallet for user', uid)
except Exception as e:
    print('Wallet check/insert error', e)
conn.commit()
conn.close()
print('Done')
