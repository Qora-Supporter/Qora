package qora;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observer;

import ntp.NTP;
import qora.account.PrivateKeyAccount;
import qora.block.Block;
import qora.block.BlockFactory;
import qora.crypto.Crypto;
import qora.transaction.Transaction;
import utils.ObserverMessage;
import utils.TransactionFeeComparator;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;

import controller.Controller;
import database.DatabaseSet;

public class BlockGenerator extends Thread
{	
	public static final int RETARGET = 10;
	public static final long MIN_BALANCE = 1l;
	public static final long MAX_BALANCE = 10000000000l;
	public static final int MIN_BLOCK_TIME = 1 * 60;
	public static final int MAX_BLOCK_TIME = 5 * 60;
	
	private boolean run = true;
	private Map<PrivateKeyAccount, Block> blocks;
	private Block solvingBlock;
	
	public void addUnconfirmedTransaction(Transaction transaction)
	{
		this.addUnconfirmedTransaction(DatabaseSet.getInstance(), transaction, true);
	}
	
	public void addUnconfirmedTransaction(DatabaseSet db, Transaction transaction, boolean process) 
	{
		//ADD TO TRANSACTION DATABASE 
		db.getTransactionsDatabase().addTransaction(transaction);
	}
	
	public List<Transaction> getUnconfirmedTransactions()
	{
		return DatabaseSet.getInstance().getTransactionsDatabase().getTransactions();
	}
	
	public void run()
	{
		while(this.run)
		{
			//CHECK IF WE ARE UPTODATE
			if(!Controller.getInstance().isUpToDate())
			{
				Controller.getInstance().update();
			}
			
			//CHECK IF WE HAVE CONNECTIONS
			//TODO REMOVE
			while(true)//if(Controller.getInstance().getStatus() == Controller.STATUS_OKE)
			{
				//GET LAST BLOCK
				byte[] lastBlockSignature = DatabaseSet.getInstance().getBlockDatabase().getLastBlockSignature();
						
				//CHECK IF DIFFERENT FOR CURRENT SOLVING BLOCK
				if(this.solvingBlock == null || !Arrays.equals(this.solvingBlock.getSignature(), lastBlockSignature))
				{
					//SET NEW BLOCK TO SOLVE
					this.solvingBlock = DatabaseSet.getInstance().getBlockDatabase().getLastBlock();
					
					//RESET BLOCKS
					this.blocks = new HashMap<PrivateKeyAccount, Block>();
				}
				
				//GENERATE NEW BLOCKS
				if(Controller.getInstance().doesWalletExists() /*&& Controller.getInstance().isWalletUnlocked()*/)
				{
					//PREVENT CONCURRENT MODIFY EXCEPTION
					List<PrivateKeyAccount> knownAccounts = Controller.getInstance().getPrivateKeyAccounts();							
					synchronized(knownAccounts)
					{
						for(PrivateKeyAccount account: knownAccounts)
						{
							if(account.getGeneratingBalance().compareTo(BigDecimal.ONE) >= 0)
							{
								//CHECK IF BLOCK FROM USER ALREADY EXISTS USE MAP ACCOUNT BLOCK EASY
								if(!this.blocks.containsKey(account))
								{	
									//GENERATE NEW BLOCK FOR USER
									this.blocks.put(account, this.generateNextBlock(DatabaseSet.getInstance(), account, this.solvingBlock));
								}
							}
						}
					}
				}
				
				//VALID BLOCK FOUND
				boolean validBlockFound = false;
						
				//CHECK IF BLOCK IS VALID
				for(PrivateKeyAccount account: this.blocks.keySet())
				{
					Block block = this.blocks.get(account);
					
					/*Date date = new Date(block.getTimestamp());
					DateFormat format = DateFormat.getDateTimeInstance();
					System.out.println(format.format(date));*/
					
					//CHECK IF BLACK TIMESTAMP IS VALID
					if(block.getTimestamp() <= NTP.getTime() && !validBlockFound)
					{
						//ADD TRANSACTIONS
						this.addUnconfirmedTransactions(DatabaseSet.getInstance(), block);
						
						//ADD TRANSACTION SIGNATURE
						block.setTransactionsSignature(this.calculateTransactionsSignature(block, account));
						
						//PASS BLOCK TO CONTROLLER
						Controller.getInstance().newBlockGenerated(block);
						
						//BLOCK FOUND
						validBlockFound = true;
					}
				}
				
				//IF NO BLOCK FOUND
				if(!validBlockFound)
				{
					//SLEEP
					try 
					{
						Thread.sleep(100);
					} 
					catch (InterruptedException e) 
					{
						e.printStackTrace();
					}
				}
			}
			/*else //TODO REMOVE
			{
				//SLEEP
				try 
				{
					Thread.sleep(100);
				} 
				catch (InterruptedException e) 
				{
					e.printStackTrace();
				}
			}*/
		}
	}
	
	public Block generateNextBlock(DatabaseSet db, PrivateKeyAccount account, Block block)
	{
		//CHECK IF ACCOUNT HAS BALANCE
		if(account.getGeneratingBalance(db) == BigDecimal.ZERO)
		{
			return null;
		}
		
		//CALCULATE SIGNATURE
		byte[] signature = this.calculateSignature(db, block, account);
		
		//CALCULATE HASH
		byte[] hash = Crypto.getInstance().digest(signature);
			
		//CONVERT HASH TO BIGINT
		BigInteger hashValue = new BigInteger(1, hash);
		
		//CALCULATE ACCOUNT TARGET
		byte[] targetBytes = new byte[32];
		Arrays.fill(targetBytes, Byte.MAX_VALUE);
		BigInteger target = new BigInteger(1, targetBytes);
								
		//DIVIDE TARGET BY BASE TARGET
		BigInteger baseTarget = BigInteger.valueOf(getBaseTarget(getNextBlockGeneratingBalance(db, block)));
		target = target.divide(baseTarget);
			
		//MULTIPLY TARGET BY USER BALANCE
		target = target.multiply(account.getGeneratingBalance(db).toBigInteger());
		
		//CALCULATE GUESSES
		//long guesses = hashValue.divide(target).longValue() + 1;
		BigInteger guesses = hashValue.divide(target).add(BigInteger.ONE);
		
		//CALCULATE TIMESTAMP
		//long timestamp = block.getTimestamp() + (guesses * 1000);
		BigInteger timestamp = guesses.multiply(BigInteger.valueOf(1000)).add(BigInteger.valueOf(block.getTimestamp()));
		
		//CHECK IF NOT HIGHER THAN MAX LONG VALUE
		if(timestamp.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) == 1)
		{
			timestamp = BigInteger.valueOf(Long.MAX_VALUE);
		}
		
		//CREATE NEW BLOCK
		int version = 1;
		Block newBlock = BlockFactory.getInstance().create(version, block.getSignature(), timestamp.longValue(), getNextBlockGeneratingBalance(db, block), account, signature);
		
		return newBlock;
	}
	
	private byte[] calculateSignature(DatabaseSet db, Block solvingBlock, PrivateKeyAccount account) 
	{	
		byte[] data = new byte[0];
		
		//WRITE PARENT GENERATOR SIGNATURE
		byte[] generatorSignature = Bytes.ensureCapacity(solvingBlock.getGeneratorSignature(), Block.GENERATOR_SIGNATURE_LENGTH, 0);
		data = Bytes.concat(data, generatorSignature);
		
		//WRITE GENERATING BALANCE
		byte[] baseTargetBytes = Longs.toByteArray(getNextBlockGeneratingBalance(db, solvingBlock));
		baseTargetBytes = Bytes.ensureCapacity(baseTargetBytes, Block.GENERATING_BALANCE_LENGTH, 0);
		data = Bytes.concat(data,baseTargetBytes);
		
		//WRITE GENERATOR
		byte[] generatorBytes = Bytes.ensureCapacity(account.getPublicKey(), Block.GENERATOR_LENGTH, 0);
		data = Bytes.concat(data, generatorBytes);
								
		//CALC SIGNATURE OF NEWBLOCKHEADER
		byte[] signature = Crypto.getInstance().sign(account, data);
		
		return signature;
	}
	
	public byte[] calculateTransactionsSignature(Block block, PrivateKeyAccount account) 
	{	
		byte[] data = block.getGeneratorSignature();
		
		//WRITE TRANSACTION SIGNATURE
		for(Transaction transaction: block.getTransactions())
		{
			data = Bytes.concat(data, transaction.getSignature());
		}
		
		return Crypto.getInstance().sign(account, data);
	}
	
	public void addUnconfirmedTransactions(DatabaseSet db, Block block)
	{
		long totalBytes = 0;
		boolean transactionProcessed;
			
		//CREATE FORK OF GIVEN DATABASE
		DatabaseSet newBlockDb = db.fork();
					
		//ORDER TRANSACTIONS BY FEE PER BYTE
		List<Transaction> orderedTransactions = new ArrayList<Transaction>(db.getTransactionsDatabase().getTransactions());
		Collections.sort(orderedTransactions, new TransactionFeeComparator());
		//Collections.sort(orderedTransactions, Collections.reverseOrder());
					
		do
		{
			transactionProcessed = false;
						
			for(Transaction transaction: orderedTransactions)
			{
				//CHECK TRANSACTION TIMESTAMP AND DEADLINE
				if(transaction.getTimestamp() <= block.getTimestamp() && transaction.getDeadline() > block.getTimestamp())
				{
					//CHECK IF VALID
					if(transaction.isValid(newBlockDb) == Transaction.VALIDATE_OKE)
					{
						//CHECK IF ENOUGH ROOM
						if(totalBytes + transaction.getDataLength() <= Block.MAX_TRANSACTION_BYTES)
						{
							//ADD INTO BLOCK
							block.addTransaction(transaction);
										
							//REMOVE FROM LIST
							orderedTransactions.remove(transaction);
										
							//PROCESS IN NEWBLOCKDB
							transaction.process(newBlockDb);
										
							//TRANSACTION PROCESSES
							transactionProcessed = true;
							break;
						}
					}
				}
						
			}
		}
		while(transactionProcessed == true);
	}
	
	public void addObserver(Observer o)
	{
		o.update(null, new ObserverMessage(ObserverMessage.LIST_TRANSACTION_TYPE, DatabaseSet.getInstance().getTransactionsDatabase().getTransactions()));
	}
	
	public static long getNextBlockGeneratingBalance(DatabaseSet db, Block block)
	{
		int height = block.getHeight(db);
		if(height % RETARGET == 0)
		{
			//CALCULATE THE GENERATING TIME FOR LAST 10 BLOCKS
			long generatingTime = block.getTimestamp();
				
			//GET FIRST BLOCK OF TARGET
			Block firstBlock = block;
			for(int i=1; i<RETARGET; i++)
			{
				firstBlock = firstBlock.getParent(db);
			}
					
			generatingTime -= firstBlock.getTimestamp();
			
			//CALCULATE EXPECTED FORGING TIME
			long expectedGeneratingTime = getBlockTime(block.getGeneratingBalance()) * RETARGET * 1000;
			
			//CALCULATE MULTIPLIER
			double multiplier = (double) expectedGeneratingTime / (double) generatingTime;
			
			//CALCULATE NEW GENERATING BALANCE
			long generatingBalance = (long) (block.getGeneratingBalance() * multiplier);
			
			return minMaxBalance(generatingBalance);
		}
		
		return block.getGeneratingBalance();
	}
		
	public static long getBaseTarget(long generatingBalance)
	{
		generatingBalance = minMaxBalance(generatingBalance);
		
		long baseTarget = generatingBalance * getBlockTime(generatingBalance);
		
		return baseTarget;
	}
	
	public static long getBlockTime(long generatingBalance)
	{
		generatingBalance = minMaxBalance(generatingBalance);
		
		double percentageOfTotal = (double) generatingBalance / MAX_BALANCE;
		long actualBlockTime = (long) (MIN_BLOCK_TIME + ((MAX_BLOCK_TIME - MIN_BLOCK_TIME) * (1 - percentageOfTotal)));
		
		return actualBlockTime;
	}
	
	private static long minMaxBalance(long generatingBalance)
	{
		if(generatingBalance < MIN_BALANCE)
		{
			return MIN_BALANCE;
		}
		
		if(generatingBalance > MAX_BALANCE)
		{
			return MAX_BALANCE;
		}
		
		return generatingBalance;
	}
	
	public void stopThread()
	{
		try 
		{
			this.run = false;
			this.join();
		} 
		catch (InterruptedException e) 
		{
			//INTERRUPTED
		}
	}
}
