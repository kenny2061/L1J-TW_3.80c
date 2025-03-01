/**
 *                            License
 * THE WORK (AS DEFINED BELOW) IS PROVIDED UNDER THE TERMS OF THIS  
 * CREATIVE COMMONS PUBLIC LICENSE ("CCPL" OR "LICENSE"). 
 * THE WORK IS PROTECTED BY COPYRIGHT AND/OR OTHER APPLICABLE LAW.  
 * ANY USE OF THE WORK OTHER THAN AS AUTHORIZED UNDER THIS LICENSE OR  
 * COPYRIGHT LAW IS PROHIBITED.
 * 
 * BY EXERCISING ANY RIGHTS TO THE WORK PROVIDED HERE, YOU ACCEPT AND  
 * AGREE TO BE BOUND BY THE TERMS OF THIS LICENSE. TO THE EXTENT THIS LICENSE  
 * MAY BE CONSIDERED TO BE A CONTRACT, THE LICENSOR GRANTS YOU THE RIGHTS CONTAINED 
 * HERE IN CONSIDERATION OF YOUR ACCEPTANCE OF SUCH TERMS AND CONDITIONS.
 * 
 */
package l1j.server.server;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import l1j.server.Config;
// import l1j.server.server.Opcodes;
import l1j.server.server.datatables.CharBuffTable;
import l1j.server.server.model.L1DragonSlayer;
import l1j.server.server.model.Getback;
import l1j.server.server.model.L1Trade;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1DollInstance;
import l1j.server.server.model.Instance.L1FollowerInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.model.Instance.L1SummonInstance;
import l1j.server.server.serverpackets.S_Disconnect;
import l1j.server.server.serverpackets.S_PacketBox;
import l1j.server.server.serverpackets.S_SummonPack;
import l1j.server.server.serverpackets.ServerBasePacket;
import l1j.server.server.utils.StreamUtil;
import l1j.server.server.utils.SystemUtil;

// Referenced classes of package l1j.server.server:
// PacketHandler, Logins, IpTable, LoginController,
// ClanTable, IdFactory
//
public class ClientThread implements Runnable, PacketOutput {

	private static Logger _log = Logger.getLogger(ClientThread.class.getName());

	private InputStream _in;

	private OutputStream _out;

	private PacketHandler _handler;

	private Account _account;

	private L1PcInstance _activeChar;

	private String _ip;

	private String _hostname;

	private Socket _csocket;

	private int _loginStatus = 0;
	
	// 3.80C Taiwan Server First Packet
	private static final byte[] FIRST_PACKET = {
	    (byte) 0x9d, (byte) 0xd1, (byte) 0xd6, (byte) 0x7a, (byte) 0xf4, 
	    (byte) 0x62, (byte) 0xe7, (byte) 0xa0, (byte) 0x66, (byte) 0x02, 
	    (byte) 0xfa
	};

	/**
	 * for Test
	 */
	protected ClientThread() {
	}

	public ClientThread(Socket socket) throws IOException {
		_csocket = socket;
		_ip = socket.getInetAddress().getHostAddress();
		if (Config.HOSTNAME_LOOKUPS) {
			_hostname = socket.getInetAddress().getHostName();
		} else {
			_hostname = _ip;
		}
		_in = socket.getInputStream();
		_out = new BufferedOutputStream(socket.getOutputStream());

		// PacketHandler 初始化
		_handler = new PacketHandler(this);
	}

	public String getIp() {
		return _ip;
	}

	public String getHostname() {
		return _hostname;
	}

	// 限制ClientThread以固定間隔自動儲存的標誌（true：限制 false：無限制）
	// 目前，執行C_LoginToServer時為false，
	// 執行 C_NewCharSelect 時變成 true
	private boolean _charRestart = true;

	public void CharReStart(boolean flag) {
		_charRestart = flag;
	}

	
	private byte[] readPacket() throws Exception {
		try {
			int hiByte = _in.read();
			int loByte = _in.read();
			if ((loByte < 0) || (hiByte < 0)) { 
				throw new RuntimeException();
			}

			final int dataLength = ((loByte << 8) + hiByte) - 2;
			if ((dataLength <= 0) || (dataLength > 65533)) {
				throw new RuntimeException();
			}
			
			byte data[] = new byte[dataLength];

			int readSize = 0;

			for (int i = 0; i != -1 && readSize < dataLength; readSize += i) {
				i = _in.read(data, readSize, dataLength - readSize);
			}

			if (readSize != dataLength) {
				_log.warning("Incomplete Packet is sent to the server, closing connection.");
				throw new RuntimeException();
			}

			return _cipher.decrypt(data);
		} catch (Exception e) {
			throw e;
		}
	}

	private long _lastSavedTime = System.currentTimeMillis();

	private long _lastSavedTime_inventory = System.currentTimeMillis();

	private Cipher _cipher;

	private void doAutoSave() throws Exception {
		if (_activeChar == null || _charRestart) {
			return;
		}
		try {
			// 自動儲存角色資料
			if (Config.AUTOSAVE_INTERVAL * 1000 < System.currentTimeMillis() - _lastSavedTime) {
				_activeChar.save();
				_lastSavedTime = System.currentTimeMillis();
			}

			// 自動儲存身上道具資料
			if (Config.AUTOSAVE_INTERVAL_INVENTORY * 1000 < System.currentTimeMillis() - _lastSavedTime_inventory) {
				_activeChar.saveInventory();
				_lastSavedTime_inventory = System.currentTimeMillis();
			}
		} catch (Exception e) {
			_log.warning("Client autosave failure.");
			_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			throw e;
		}
	}

	@Override
	public void run() {
		

		/*
		 * 在一定程度上限制來自客戶端的資料包。 原因：有頻繁誤檢詐欺的風險。
		 * ex1. 如果伺服器過載，當負載下降時，它會立即處理所有客戶端資料包，從而導致錯誤處理。
		 * ex2. 如果伺服器端網路（下游）出現延遲，客戶端資料包將同時流入，導致處理錯誤。
		 * ex3. 若客戶端網路（上游）有延遲，則同樣適用。
		 *
		 * 在限制詐欺之前，需要檢視詐欺偵測方法。
		 */
		HcPacket movePacket = new HcPacket(M_CAPACITY);
		HcPacket hcPacket = new HcPacket(H_CAPACITY);
		GeneralThreadPool.getInstance().execute(movePacket);
		GeneralThreadPool.getInstance().execute(hcPacket);
		
		String keyHax ="";
		int key = 0;
		byte Bogus = 0;
		
		try {
			/** 採取亂數取seed */
			keyHax = Integer.toHexString((int) (Math.random() * 2147483647) + 1);
			key = Integer.parseInt(keyHax, 16);

			Bogus = (byte) (FIRST_PACKET.length + 7);
			_out.write(Bogus & 0xFF);
			_out.write(Bogus >> 8 & 0xFF);
			_out.write(Opcodes.S_OPCODE_INITPACKET);// 3.7C Taiwan Server
			_out.write((byte) (key & 0xFF));
			_out.write((byte) (key >> 8 & 0xFF));
			_out.write((byte) (key >> 16 & 0xFF));
			_out.write((byte) (key >> 24 & 0xFF));

			_out.write(FIRST_PACKET);
			_out.flush();

		}
		catch (Throwable e) {
			try {
				_log.info("異常用戶端(" + _hostname + ") 連結到伺服器, 已中斷該連線。");
				StreamUtil.close(_out, _in);
				if (_csocket != null) {
					_csocket.close();
					_csocket = null;
				}
				return;
			} catch (Throwable ex) {
				return;
			}
		}
		finally {
		
		}



		try {
			_log.info("(" + _hostname + ") 連結到伺服器。");
			System.out.println("使用了 " + SystemUtil.getUsedMemoryMB() + "MB 的記憶體");
			System.out.println("等待客戶端連接...");
			
			ClientThreadObserver observer = new ClientThreadObserver(Config.AUTOMATIC_KICK * 60 * 1000); // 自動斷線的時間（單位:毫秒）

			// 是否啟用自動踢人
			if (Config.AUTOMATIC_KICK > 0) {
				observer.start();
			}
			
			_cipher = new Cipher(key);

			while (true) {
				doAutoSave();

				byte data[] = null;
				try {
					data = readPacket();
				} catch (Exception e) {
					break;
				}
				// _log.finest("[C]\n" + new
				// ByteArrayUtil(data).dumpToString());

				int opcode = data[0] & 0xFF;

				// 處理多重登入
				if (opcode == Opcodes.C_OPCODE_BEANFUNLOGINPACKET || opcode == Opcodes.C_OPCODE_CHANGECHAR) {
					_loginStatus = 1;
				}
				if (opcode == Opcodes.C_OPCODE_LOGINTOSERVER) {
					if (_loginStatus != 1) {
						continue;
					}
				}
				if (opcode == Opcodes.C_OPCODE_LOGINTOSERVEROK) {
					_loginStatus = 0;
				}

				if (opcode != Opcodes.C_OPCODE_KEEPALIVE) {
					// C_OPCODE_KEEPALIVE以外の何かしらのパケットを受け取ったらObserverへ通知
					observer.packetReceived();
				}
				// 如果目前角色為 null 在字元選擇之前，因此執行所有操作而不選擇操作碼。
				if (_activeChar == null) {
					_handler.handlePacket(data, _activeChar);
					continue;
				}

				// 從現在開始進行處理，防止PacketHandler的處理狀態影響ClientThread
				// 目的是選擇Opcodes並分開ClientThread和PacketHandler

				// 要處理的 OPCODE
				// 切換角色、丟道具到地上、刪除身上道具
				if (opcode == Opcodes.C_OPCODE_CHANGECHAR
						|| opcode == Opcodes.C_OPCODE_DROPITEM
						|| opcode == Opcodes.C_OPCODE_DELETEINVENTORYITEM) {
					_handler.handlePacket(data, _activeChar);
				} else if (opcode == Opcodes.C_OPCODE_MOVECHAR) {
					// 為了確保即時的移動，將移動的封包獨立出來處理
					movePacket.requestWork(data);
				} else {
					// 處理其他數據的傳遞
					hcPacket.requestWork(data);
				}
			}
		} catch (Throwable e) {
			_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			_log.log(Level.SEVERE, e.getMessage(), e.fillInStackTrace());
		} finally {
			try {
				if (_activeChar != null) {
					quitGame(_activeChar);

					synchronized (_activeChar) {
						// 從線上中登出角色
						_activeChar.logout();
						setActiveChar(null);
					}
				}
				// 玩家離線時, online=0
				if (getAccount() != null) {
					Account.online(getAccount(), false);
				}

				// 送出斷線的封包
				sendPacket(new S_Disconnect());

				StreamUtil.close(_out, _in);
				if (_csocket != null) {
					_csocket.close();
					_csocket = null;
				}
			} catch (Exception e) {
				_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			} finally {
				LoginController.getInstance().logout(this);
			}
		}
		_csocket = null;
		_log.fine("Server thread[C] stopped");
		if (_kick < 1) {
			_log.info("(" + getAccountName() + ":" + _hostname + ")連線終止。");
			System.out.println("使用了 " + SystemUtil.getUsedMemoryMB()
					+ "MB 的記憶體");
			System.out.println("等待客戶端連接...");
			if (getAccount() != null) {
				Account.online(getAccount(), false);
			}
		}
		return;
	}

	private int _kick = 0;

	public void kick() {
		try {
			Account.online(getAccount(), false);
			sendPacket(new S_Disconnect());
			_kick = 1;
			StreamUtil.close(_out, _in);
			if (_csocket != null) {
				_csocket.close();
				_csocket = null;
			}
		}
		catch (Throwable ex) {
			
		}
	}

	private static final int M_CAPACITY = 3; // 一邊移動的最大封包量

	private static final int H_CAPACITY = 2;// 一方接受的最高限額所需的行動

	// 帳號處理的程序
	class HcPacket implements Runnable {
		private final Queue<byte[]> _queue;

		private PacketHandler _handler;

		public HcPacket() {
			_queue = new ConcurrentLinkedQueue<byte[]>();
			_handler = new PacketHandler(ClientThread.this);
		}

		public HcPacket(int capacity) {
			_queue = new LinkedBlockingQueue<byte[]>(capacity);
			_handler = new PacketHandler(ClientThread.this);
		}

		public void requestWork(byte data[]) {
			_queue.offer(data);
		}

		@Override
		public void run() {
			byte[] data;
			while (_csocket != null) {
				data = _queue.poll();
				if (data != null) {
					try {
						_handler.handlePacket(data, _activeChar);
					} catch (Exception e) {
					}
				} else {
					try {
						Thread.sleep(10);
					} catch (Exception e) {
					}
				}
			}
			return;
		}
	}

	private static Timer _observerTimer = new Timer();

	// 定時監控客戶端
	class ClientThreadObserver extends TimerTask {
		private int _checkct = 1;

		private final int _disconnectTimeMillis;

		public ClientThreadObserver(int disconnectTimeMillis) {
			_disconnectTimeMillis = disconnectTimeMillis;
		}

		public void start() {
			_observerTimer.scheduleAtFixedRate(ClientThreadObserver.this, 0, _disconnectTimeMillis);
		}

		@Override
		public void run() {
			try {
				if (_csocket == null) {
					cancel();
					return;
				}

				if (_checkct > 0) {
					_checkct = 0;
					return;
				}

				// 選角色之前 或 正在個人商店
				if (_activeChar == null || _activeChar != null && !_activeChar.isPrivateShop()) 
				{
					kick();
					_log.warning("一定時間沒有收到封包回應，所以強制切斷 (" + _hostname + ") 的連線。");
					Account.online(getAccount(), false);
					cancel();
					return;
				}
			} catch (Exception e) {
				_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
				cancel();
			}
		}

		public void packetReceived() {
			_checkct++;
		}
	}

	@Override
	public void sendPacket(ServerBasePacket packet) {
		synchronized (this) {
			try {
				byte content[] = packet.getContent();
				byte data[] = Arrays.copyOf(content, content.length);
				_cipher.encrypt(data);
				int length = data.length + 2;

				_out.write(length & 0xff);
				_out.write(length >> 8 & 0xff);
				_out.write(data);
				_out.flush();
			} catch (Exception e) {
			}
		}
	}

	public void close() throws IOException {
		if (_csocket != null) {
			_csocket.close();
			_csocket = null;
		}
	}

	public void setActiveChar(L1PcInstance pc) {
		_activeChar = pc;
	}

	public L1PcInstance getActiveChar() {
		return _activeChar;
	}

	public void setAccount(Account account) {
		_account = account;
	}

	public Account getAccount() {
		return _account;
	}

	public String getAccountName() {
		if (_account == null) {
			return null;
		}
		return _account.getName();
	}

	public static void quitGame(L1PcInstance pc) {
		// 如果死掉回到城中，設定飽食度
		if (pc.isDead()) {
			try {
				Thread.sleep(2000);// 暫停該執行續，優先權讓給expmonitor
				int[] loc = Getback.GetBack_Location(pc, true);
				pc.setX(loc[0]);
				pc.setY(loc[1]);
				pc.setMap((short) loc[2]);
				pc.setCurrentHp(pc.getLevel());
				pc.set_food(40);
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		// 終止交易
		if (pc.getTradeID() != 0) { // トレード中
			L1Trade trade = new L1Trade();
			trade.TradeCancel(pc);
		}

		// 終止決鬥
		if (pc.getFightId() != 0) {
			L1PcInstance fightPc = (L1PcInstance) L1World.getInstance().findObject(pc.getFightId());
			pc.setFightId(0);
			if (fightPc != null) {
				fightPc.setFightId(0);
				fightPc.sendPackets(new S_PacketBox(S_PacketBox.MSG_DUEL, 0, 0));
			}
		}

		// 離開組隊
		if (pc.isInParty()) { // 如果有組隊
			pc.getParty().leaveMember(pc);
		}

		// TODO: 離開聊天組隊(?)
		if (pc.isInChatParty()) { // 如果在聊天組隊中(?)
			pc.getChatParty().leaveMember(pc);
		}

		// 移除世界地圖上的寵物
		// 變更召喚怪物的名稱
		for (L1NpcInstance petNpc : pc.getPetList().values()) {
			if (petNpc instanceof L1PetInstance) {
				L1PetInstance pet = (L1PetInstance) petNpc;
				// 停止飽食度計時
				pet.stopFoodTimer(pet);
				pet.dropItem();
				pc.getPetList().remove(pet.getId());
				pet.deleteMe();
			} else if (petNpc instanceof L1SummonInstance) {
				L1SummonInstance summon = (L1SummonInstance) petNpc;
				for (L1PcInstance visiblePc : L1World.getInstance().getVisiblePlayer(summon)) {
					visiblePc.sendPackets(new S_SummonPack(summon, visiblePc,false));
				}
			}
		}

		// 移除世界地圖上的魔法娃娃
		for (L1DollInstance doll : pc.getDollList().values())
			doll.deleteDoll();

		// 重新建立跟隨者
		for (L1FollowerInstance follower : pc.getFollowerList().values()) {
			follower.setParalyzed(true);
			follower.spawn(follower.getNpcTemplate().get_npcId(),follower.getX(), follower.getY(), follower.getHeading(),follower.getMapId());
			follower.deleteMe();
		}

		// 刪除屠龍副本此玩家紀錄
		if (pc.getPortalNumber() != -1) {
			L1DragonSlayer.getInstance().removePlayer(pc, pc.getPortalNumber());
		}

		// 儲存魔法狀態
		CharBuffTable.DeleteBuff(pc);
		CharBuffTable.SaveBuff(pc);
		pc.clearSkillEffectTimer();
		l1j.server.server.model.game.L1PolyRace.getInstance().checkLeaveGame(pc);

		// 停止玩家的偵測
		pc.stopEtcMonitor();
		// 設定線上狀態為下線
		pc.setOnlineStatus(0);
		// 設定帳號為下線
		//Account account = Account.load(pc.getAccountName());
		//Account.online(account, false);
		// 設定帳號的角色為下線
		Account account = Account.load(pc.getAccountName());
		Account.OnlineStatus(account, false);

		try {
			pc.save();
			pc.saveInventory();
		} catch (Exception e) {
			_log.log(Level.SEVERE, e.getLocalizedMessage(), e);
		}
	}
}
